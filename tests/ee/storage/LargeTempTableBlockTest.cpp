/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include <getopt.h>
#include <chrono>
#include <random>
#include <tuple>
#include <utility>


#include "boost/foreach.hpp"
#include "boost/lexical_cast.hpp"

#include "common/tabletuple.h"
#include "common/TupleSchema.h"
#include "common/TupleSchemaBuilder.h"

#include "storage/LargeTempTableBlock.h"
#include "storage/LargeTempTable.h"
#include "storage/tablefactory.h"

#include "harness.h"

#include "test_utils/LargeTempTableTopend.hpp"
#include "test_utils/Tools.hpp"
#include "test_utils/TupleComparingTest.hpp"
#include "test_utils/UniqueEngine.hpp"
#include "test_utils/UniqueTable.hpp"

using namespace voltdb;

int NUM_SORTS = 1; // set this to a higher number via command line for benchmark
int32_t VARCHAR_LENGTH = 256; // set this via command line if desired
int32_t INLINE_PADDING = 64; // set this via command line if desired

class LargeTempTableBlockTest : public TupleComparingTest {
public:

    LargeTempTableBlockTest()
        : m_rd()
        , m_gen(m_rd())
        , m_dis()
    {
    }

protected:
    TupleSchema* getSchemaOfLength(int32_t varcharLengthBytes, int32_t inlinePadding) {
        TupleSchemaBuilder builder(inlinePadding + 1);
        builder.setColumnAtIndex(0, VALUE_TYPE_VARCHAR, varcharLengthBytes, true, true);
        for (int i = 0; i < inlinePadding; ++i) {
            builder.setColumnAtIndex(i + 1, VALUE_TYPE_TINYINT);
        }
        return builder.build();
    }

    void fillBlock(LargeTempTableBlock* block) {
        StandAloneTupleStorage storage{block->schema()};
        TableTuple tupleToInsert = storage.tuple();

        for (int i = 1; i < block->schema()->columnCount(); ++i) {
            tupleToInsert.setNValue(i, Tools::nvalueFromNative(int8_t(i)));
        }

        do {
            tupleToInsert.setNValue(0, Tools::nvalueFromNative(generateRandomString(VARCHAR_LENGTH)));
        }
        while (block->insertTuple(tupleToInsert));
    }

    void verifySortedBlock(const LargeTempTableBlock& block) {
        auto it = block.begin();
        std::string lastValue = Tools::nativeFromNValue<std::string>(it->toTableTuple(block.schema()).getNValue(0));
        ++it;
        for (; it != block.end(); ++it) {
            std::string curValue = Tools::nativeFromNValue<std::string>(it->toTableTuple(block.schema()).getNValue(0));
            ASSERT_TRUE(lastValue.compare(curValue) <= 0);
            lastValue = curValue;
        }
    }

    void summarize(const LargeTempTableBlock& block, std::chrono::microseconds totalMicros) {
        double totalTimeMillis = totalMicros.count() / 1000.0;
        double millisPerSort = totalTimeMillis / NUM_SORTS;
        std::cout << "\n\nPerformed " << NUM_SORTS << " sorts of " << block.activeTupleCount() << " tuples:\n";
        const TableTuple firstTuple = block.begin()->toTableTuple(block.schema());
        std::cout << "    Inline tuple length: " << firstTuple.tupleLength() << "\n";
        std::cout << "    Non-inlined data per tuple: " << firstTuple.getNonInlinedMemorySizeForTempTable() << "\n";
        std::cout << "Total time: " << totalTimeMillis / 1000.0 << " seconds\n";
        std::cout << "    -->  Time per sort: " << millisPerSort << " ms\n\n";
    }

    std::string generateRandomString(std::size_t length) {
        std::ostringstream oss;

        std::size_t curLen = 0;
        while (curLen < length) {
            std::string numStr = boost::lexical_cast<std::string>(m_dis(m_gen));
            if (curLen + numStr.length() > length) {
                numStr = numStr.substr(0, length - curLen);
            }

            oss << numStr;
            curLen += numStr.length();
        }

        assert(curLen == length);
        return oss.str();
    }


private:

    std::random_device m_rd;
    std::mt19937 m_gen;
    std::uniform_int_distribution<int64_t> m_dis;
};

TEST_F(LargeTempTableBlockTest, iterator) {
    UniqueEngine engine = UniqueEngineBuilder().build();

    typedef std::tuple<int64_t, std::string, boost::optional<int32_t>> Tuple;
    TupleSchema *schema = Tools::buildSchema<Tuple>();

    LargeTempTableBlock block{0, schema};
    LargeTempTableBlock::iterator it = block.begin();
    LargeTempTableBlock::iterator itEnd = block.end();
    ASSERT_EQ(it, itEnd);

    // Insert some tuples into the block
    std::vector<Tuple> stdTuples{
        Tuple{0, "foo", boost::none},
        Tuple{1, "bar", 37},
        Tuple{2, "baz", 49},
        Tuple{3, "bugs", 96},
    };

    StandAloneTupleStorage tupleStorage{schema};
    TableTuple tupleToInsert = tupleStorage.tuple();
    BOOST_FOREACH(auto stdTuple, stdTuples) {
        Tools::initTuple(&tupleToInsert, stdTuple);
        block.insertTuple(tupleToInsert);
    }

    // Use the iterator to access inserted tuples
    it = block.begin();
    itEnd = block.end();
    int i = 0;
    while (it != itEnd) {
        TableTuple tuple = it->toTableTuple(schema);
        ASSERT_TUPLES_EQ(stdTuples[i], tuple);
        ++it;
        ++i;
    }

    ASSERT_EQ(stdTuples.size(), i);

    // This also works with BOOST_FOREACH
    i = 0;
    BOOST_FOREACH(LargeTempTableBlock::Tuple& lttTuple, block) {
        char* storage = reinterpret_cast<char*>(&lttTuple);
        TableTuple tuple{storage, schema};
        ASSERT_TUPLES_EQ(stdTuples[i], tuple);
        ++i;
    }

    // Test *it++, which the standard says should work.
    {
        it = block.begin();
        LargeTempTableBlock::Tuple& lttTuple = *it++;
        ASSERT_TUPLES_EQ(stdTuples[0], lttTuple.toTableTuple(schema));
        ASSERT_TUPLES_EQ(stdTuples[1], it->toTableTuple(schema));
    }

    // Decrement should also work.
    {
        // post-decrement
        LargeTempTableBlock::Tuple& lttTuple = *it--;
        ASSERT_TUPLES_EQ(stdTuples[1], lttTuple.toTableTuple(schema));
        ASSERT_TUPLES_EQ(stdTuples[0], it->toTableTuple(schema));

        ++it;
        // pre-decrement
        --it;
        ASSERT_TUPLES_EQ(stdTuples[0], it->toTableTuple(schema));
    }

    // assign-add and assign-subtract
    it = block.begin();
    it += 3;
    ASSERT_TUPLES_EQ(stdTuples[3], it->toTableTuple(schema));

    it -= 2;
    ASSERT_TUPLES_EQ(stdTuples[1], it->toTableTuple(schema));

    // binary add and subtract
    it = block.begin();
    LargeTempTableBlock::iterator it2 = it + 3;
    ASSERT_TUPLES_EQ(stdTuples[3], it2->toTableTuple(schema));
    ASSERT_TUPLES_EQ(stdTuples[0], it->toTableTuple(schema));

    it = it2 - 2;
    ASSERT_TUPLES_EQ(stdTuples[1], it->toTableTuple(schema));
    ASSERT_TUPLES_EQ(stdTuples[3], it2->toTableTuple(schema));

    // constant LHS operand uses non-member function
    it2 = 1 + it;
    ASSERT_TUPLES_EQ(stdTuples[2], it2->toTableTuple(schema));

    // iterator subtraction
    ASSERT_EQ(stdTuples.size(), block.end() - block.begin());

    // operator[]
    it = block.begin();
    ASSERT_TUPLES_EQ(stdTuples[0], it[0].toTableTuple(schema));
    ASSERT_TUPLES_EQ(stdTuples[3], it[3].toTableTuple(schema));

    // relational operators
    ASSERT_TRUE(block.end() > block.begin());
    ASSERT_TRUE(block.end() >= block.begin());
    ASSERT_TRUE(block.end() >= block.end());
    ASSERT_TRUE(block.begin() < block.end());
    ASSERT_TRUE(block.begin() <= block.end());
    ASSERT_TRUE(block.begin() <= block.begin());

    // const_iterator
    LargeTempTableBlock::const_iterator itc = block.cbegin();
    ASSERT_TUPLES_EQ(stdTuples[0], itc[0].toTableTuple(schema));
    // This does not compile; can't convert const_iterator to iterator
    // LargeTempTableBlock::iterator nonConstIt = block.cbegin();
    //
    // This compiles, since you can convert an iterator to a const_iterator:
    itc = block.begin();

    const LargeTempTableBlock* constBlock = &block;
    auto anotherConstIt = constBlock->begin();
    // This is also a const iterator, so this does not compile
    // anotherConstIt->toTableTuple(schema).setNValue(0, Tools::nvalueFromNative(int64_t(77)));
    ASSERT_TUPLES_EQ(stdTuples[0], anotherConstIt->toTableTuple(schema));
}

namespace {

 struct FirstFieldComparator {
     // implements less-than
     bool operator()(const TableTuple& t0, const TableTuple& t1) const {
        NValue nval0 = t0.getNValue(0);
        NValue nval1 = t1.getNValue(0);
        int cmp =  nval0.compare(nval1);
        return cmp == VALUE_COMPARE_LESSTHAN;
    }
};

template<class Compare>
class LTTBlockSorter {
public:
    typedef LargeTempTableBlock::iterator iterator;
    typedef iterator::difference_type difference_type;

    LTTBlockSorter(const TupleSchema* schema, const Compare& compare)
        : m_schema(schema)
        , m_tempStorage(schema)
        , m_tempTuple(m_tempStorage.tuple())
        , m_compare(compare)
    {
    }

    void sort(LargeTempTableBlock::iterator beginIt,
              LargeTempTableBlock::iterator endIt) {
        while (true) {
            difference_type numElems = endIt - beginIt;
            switch (numElems) {
            case 0:
            case 1:
                return;
            case 2: insertionSort<2>(beginIt); return;
            case 3: insertionSort<3>(beginIt); return;
            case 4: insertionSort<4>(beginIt); return;
            // case 5: insertionSort<5>(beginIt); return;
            // case 6: insertionSort<6>(beginIt); return;
            default:
                break;
            }

            iterator pivot = endIt - 1;
            difference_type i = -1; // index of last less-than-pivot element
            for (difference_type j = 0; j < numElems - 1; ++j) {
                iterator it = beginIt + j;
                if (m_compare(it->toTableTuple(m_schema), pivot->toTableTuple(m_schema))) {
                    ++i;
                    swap(*it, beginIt[i]);
                }
            }

            // move the pivot to the correct place
            ++i; // index of first greater-than-or-equal-to-pivot element
            if (m_compare(pivot->toTableTuple(m_schema), beginIt[i].toTableTuple(m_schema))) {
                swap(*pivot, beginIt[i]);
            }

            pivot = beginIt + i; // pivot is now in correct ordinal position

            // Make recursive call for smaller partition,
            // and use tail recursion elimination for larger one.
            if (pivot - beginIt > endIt - (pivot + 1))  {
                sort(pivot + 1, endIt);
                endIt = pivot;
            }
            else {
                sort(beginIt, pivot);
                beginIt = pivot + 1;
            }
        }
    }

private:

    template<int N>
    void insertionSort(LargeTempTableBlock::iterator beginIt) {
        assert (N > 1);

        for (difference_type i = 0; i < N; ++i) {
            int j = i;
            while (j > 0 && m_compare(beginIt[j].toTableTuple(m_schema), beginIt[j - 1].toTableTuple(m_schema))) {
                swap(beginIt[j - 1], beginIt[j]);
                --j;
            }
        }
    }

    void swap(LargeTempTableBlock::Tuple& t0,
              LargeTempTableBlock::Tuple& t1) {
        if (&t0 != &t1) {
            int tupleLength = m_tempTuple.tupleLength();
            char* tempBuffer = m_tempTuple.address();
            char* buf0 = reinterpret_cast<char*>(&t0);
            char* buf1 = reinterpret_cast<char*>(&t1);
            ::memcpy(tempBuffer, buf0, tupleLength);
            ::memcpy(buf0, buf1, tupleLength);
            ::memcpy(buf1, tempBuffer, tupleLength);
        }
    }

    const TupleSchema* m_schema;
    StandAloneTupleStorage m_tempStorage;
    TableTuple m_tempTuple;
    const Compare& m_compare;
};

}

TEST_F(LargeTempTableBlockTest, sortTuplesCustom) {
    UniqueEngine engine = UniqueEngineBuilder().build();

    typedef std::tuple<std::string> Tuple;
    TupleSchema *schema = getSchemaOfLength(VARCHAR_LENGTH, INLINE_PADDING);
    LargeTempTableBlock block{0, schema};

    LTTBlockSorter<FirstFieldComparator> sorter{schema, FirstFieldComparator()};

    std::chrono::microseconds totalSortDurationMicros{0};

    for (int i = 0; i < NUM_SORTS; ++i) {
        block.clearForTest();
        fillBlock(&block);

        auto startTime = std::chrono::high_resolution_clock::now();
        sorter.sort(block.begin(), block.end());
        auto endTime = std::chrono::high_resolution_clock::now();
        totalSortDurationMicros += std::chrono::duration_cast<std::chrono::microseconds>(endTime - startTime);

        if (i == NUM_SORTS - 1) {
            verifySortedBlock(block);
        }
    }

    if (NUM_SORTS > 1) {
        summarize(block, totalSortDurationMicros);
    }
}

TEST_F(LargeTempTableBlockTest, sortTuplesStdSort) {
    UniqueEngine engine = UniqueEngineBuilder().build();

    typedef std::tuple<std::string> Tuple;
    TupleSchema *schema = getSchemaOfLength(VARCHAR_LENGTH, INLINE_PADDING);
    LargeTempTableBlock blockInput{0, schema};
    LargeTempTableBlock blockOutput{1, schema};

    std::chrono::microseconds totalSortDurationMicros{0};

    for (int i = 0; i < NUM_SORTS; ++i) {
        blockInput.clearForTest();
        blockOutput.clearForTest();

        fillBlock(&blockInput);

        auto startTime = std::chrono::high_resolution_clock::now();
        std::vector<TableTuple> ttVector;
        BOOST_FOREACH (auto& tuple, blockInput) {
            ttVector.push_back(tuple.toTableTuple(schema));
        }

        std::sort(ttVector.begin(), ttVector.end(), FirstFieldComparator{});

        // Copy all the non-inlined data as-is
        blockOutput.copyNonInlinedData(blockInput);

        BOOST_FOREACH (TableTuple& tuple, ttVector) {
            bool success = blockOutput.insertTupleRelocateNonInlinedFields(tuple, blockInput.address());
            ASSERT_TRUE(success);
        }
        auto endTime = std::chrono::high_resolution_clock::now();
        totalSortDurationMicros += std::chrono::duration_cast<std::chrono::microseconds>(endTime - startTime);

        if (i == NUM_SORTS - 1) {
            verifySortedBlock(blockOutput);
        }
    }

    if (NUM_SORTS > 1) {
        summarize(blockOutput, totalSortDurationMicros);
    }
}

namespace {

class LttSortRun {
public:
    LttSortRun(LargeTempTable* table)
        : m_table(table)
        , m_iterator(m_table->iteratorDeletingAsWeGo())
        , m_curTuple(m_table->schema())
    {
        m_table->incrementRefcount();
    }

    ~LttSortRun() {
        if (m_table) {
            m_iterator = m_table->iteratorDeletingAsWeGo(); // unpins block if scan in progress
            m_table->decrementRefcount();
        }
    }

    void init() {
        m_iterator = m_table->iteratorDeletingAsWeGo();
        m_iterator.next(m_curTuple); // pins first block in LTT block cache
    }

    bool insertTuple(TableTuple& tuple) {
        return m_table->insertTuple(tuple);
    }

    void finishInserts() {
        m_table->finishInserts();
    }

    TableTuple& currentTuple() {
        return m_curTuple;
    }

    const TableTuple& currentTuple() const {
        return m_curTuple;
    }

    std::string debug() const {
        std::ostringstream oss;
        oss << "sort run with blocks: ";
        BOOST_FOREACH(int64_t id, m_table->getBlockIds()) {
            oss << id << " ";
        }

        return oss.str();
    }

    bool advance() {
        return m_iterator.next(m_curTuple);
    }

    LargeTempTable* releaseTable() {
        LargeTempTable* tbl = m_table;
        m_table = NULL;
        return tbl;
    }

    LargeTempTable* peekTable() {
        return m_table;
    }

    int64_t tupleCount() const {
        return m_table->activeTupleCount();
    }

private:
    LargeTempTable* m_table;
    TableIterator m_iterator;
    TableTuple m_curTuple;
};

typedef std::shared_ptr<LttSortRun> LttSortRunPtr;

template<class TupleComparator>
struct LttSortRunComparator {
    LttSortRunComparator(const TupleComparator& tupleComparator)
        : m_tupleComparator(tupleComparator)
    {
    }

    // Should implement greater-than
    bool operator()(const LttSortRunPtr& run0, const LttSortRunPtr& run1) {
        const TableTuple& tuple0 = run0->currentTuple();
        const TableTuple& tuple1 = run1->currentTuple();

        // transpose arguments to get greater-than instead of
        // less-than.
        return m_tupleComparator(tuple1, tuple0);
    }

private:
    const TupleComparator& m_tupleComparator;
};

template<class Compare>
bool lessThanOrEqual(const Compare& lessThan, const TableTuple tuple0, const TableTuple tuple1) {
    if (lessThan(tuple0, tuple1)) {
        return true;
    }

    if (lessThan(tuple1, tuple0)) { // greater than
        return false;
    }

    return true; // equal
}

bool verifySortedTable(int64_t expectedTupleCount, LargeTempTable* table) {
    Pool* pool = ExecutorContext::getTempStringPool();
    FirstFieldComparator ffCompare;

    StandAloneTupleStorage prevTupleStorage(table->schema());
    TableTuple prevTuple = prevTupleStorage.tuple();

    TableIterator verifyIt = table->iterator();
    TableTuple verifyTuple(table->schema());
    bool success = verifyIt.next(verifyTuple);
    if (! success) {
        std::cerr << "verifySortedTable failed; no tuples" << std::endl;
        return false;
    }

    prevTuple.copyForPersistentInsert(verifyTuple, pool);

    int tupleNum = 1;
    while (verifyIt.next(verifyTuple)) {
        success = lessThanOrEqual(ffCompare, prevTuple, verifyTuple);
        if (!success) {
            std::cerr << "Failed to verify " << tupleNum << "th tuple:\n";
            std::cerr << "    prev tuple: " << prevTuple.debug() << "\n";
            std::cerr << "    curr tuple: " << verifyTuple.debug() << std::endl;
            return false;
        }
        prevTuple.copyForPersistentInsert(verifyTuple, pool);
        ++tupleNum;
    }

    if (expectedTupleCount != tupleNum) {
        std::cerr << "Failed to verify table; tuple count wrong: "
                  << "expected " << expectedTupleCount << ", actual " << tupleNum
                  << std::endl;
        return false;
    }

    return true;
}

}

TEST_F(LargeTempTableBlockTest, mergeSortedBlocks) {
    UniqueEngine engine = UniqueEngineBuilder()
        .setTopend(std::unique_ptr<LargeTempTableTopend>(new LargeTempTableTopend()))
        .build();
    LargeTempTableBlockCache* lttBlockCache = ExecutorContext::getExecutorContext()->lttBlockCache();

    TupleSchema *schema = getSchemaOfLength(VARCHAR_LENGTH, INLINE_PADDING);
    std::vector<std::string> names;
    names.push_back("strfld");
    for (int i = 1; i < schema->columnCount(); ++i) {
        names.push_back(boost::lexical_cast<std::string>(i));
    }
    auto ltt = makeUniqueTable(TableFactory::buildLargeTempTable("ltmp", schema, names));

    StandAloneTupleStorage storage{schema};
    TableTuple tupleToInsert = storage.tuple();
    for (int i = 1; i < schema->columnCount(); ++i) {
        tupleToInsert.setNValue(i, Tools::nvalueFromNative(int8_t(i)));
    }

    std::cout << "Generating random data"; std::cout.flush();
    const int NUM_INPUT_BLOCKS = 25;
    int numOriginalTuples = 0;

    while (ltt->allocatedBlockCount() < 2) {
        tupleToInsert.setNValue(0, Tools::nvalueFromNative(generateRandomString(VARCHAR_LENGTH)));
        ltt->insertTuple(tupleToInsert);
        ++numOriginalTuples;
    }

    int64_t tuplesPerBlock = ltt->activeTupleCount() - 1;

    int64_t tuplesToInsertThisBlock = tuplesPerBlock - 1;
    for (int j = 0; j < NUM_INPUT_BLOCKS - 1; ++j) {
        for (int64_t i = 1; i < tuplesPerBlock; ++i) {
            tupleToInsert.setNValue(0, Tools::nvalueFromNative(generateRandomString(VARCHAR_LENGTH)));
            ltt->insertTuple(tupleToInsert);
            ++numOriginalTuples;
        }

        tuplesToInsertThisBlock = tuplesPerBlock;
    }

    ASSERT_EQ(NUM_INPUT_BLOCKS, ltt->allocatedBlockCount());

    ltt->finishInserts();
    std::cout << ". "; std::cout.flush();

    // --------------------
    // Input table complete
    // Now sort it!!
    // --------------------

    std::cout << "Sorting..."; std::cout.flush();
    auto startTime = std::chrono::high_resolution_clock::now();

    // Sort each block and create a bunch of 1-block sort runs to be merged below
    std::queue<LttSortRunPtr> sortRunQueue;
    LTTBlockSorter<FirstFieldComparator> sorter{schema, FirstFieldComparator{}};
    auto it = ltt->getBlockIds().begin();
    while (it != ltt->getBlockIds().end()) {
        int64_t blockId = *it;
        it = ltt->disownBlock(it);
        LargeTempTableBlock* block = lttBlockCache->fetchBlock(blockId);
        sorter.sort(block->begin(), block->end());
        lttBlockCache->invalidateStoredCopy(block);
        block->unpin();
        LargeTempTable* table = TableFactory::buildCopiedLargeTempTable("ltbl", ltt.get());
        table->inheritBlock(blockId);
        LttSortRunPtr sortRun{new LttSortRun(table)};
        sortRunQueue.push(sortRun);
    }

    ASSERT_EQ(NUM_INPUT_BLOCKS, sortRunQueue.size());

    // Now merge single-block runs into longer runs, until there is just one left

    const int MERGE_FACTOR = 11;
    while (sortRunQueue.size() != 1) {
        typedef LttSortRunComparator<FirstFieldComparator> SRComparator;
        typedef std::priority_queue<LttSortRunPtr, std::vector<LttSortRunPtr>, SRComparator> SortRunPriorityQueue;
        SortRunPriorityQueue mergeHeap{SRComparator{FirstFieldComparator{}}};

        int64_t expectedMergeOutputTupleCount = 0;
        for (int i = 0; i < MERGE_FACTOR; ++i) {
            if (sortRunQueue.empty()) {
                break;
            }

            LttSortRunPtr run = sortRunQueue.front();
            sortRunQueue.pop();
            run->init();
            expectedMergeOutputTupleCount += run->tupleCount();
            mergeHeap.push(run);
        }

        LttSortRunPtr outputSortRun(new LttSortRun(TableFactory::buildCopiedLargeTempTable("ltbl", ltt.get())));
        while (mergeHeap.size() > 0) {
            LttSortRunPtr run = mergeHeap.top();
            mergeHeap.pop();

            outputSortRun->insertTuple(run->currentTuple());
            bool stillMoreTuplesInSortRun = run->advance();
            if (stillMoreTuplesInSortRun) {
                mergeHeap.push(run);
            }
        }

        outputSortRun->finishInserts();
        sortRunQueue.push(outputSortRun);
    }

    auto endTime = std::chrono::high_resolution_clock::now();
    auto totalSortDurationMicros = std::chrono::duration_cast<std::chrono::microseconds>(endTime - startTime);
    std::cout << "sorted " << numOriginalTuples << " tuples (" << NUM_INPUT_BLOCKS << " blocks) in "
              << (totalSortDurationMicros.count() / 1000000.0) << " seconds. ";

    auto lttOutput = makeUniqueTable(sortRunQueue.front()->releaseTable());
    sortRunQueue.pop();
    ASSERT_EQ(numOriginalTuples, lttOutput->activeTupleCount());
    ASSERT_TRUE(verifySortedTable(numOriginalTuples, lttOutput.get()));
}


int main(int argc, char* argv[]) {
    int opt;
    while ((opt = getopt(argc, argv, "hn:v:i:")) != -1) {
        switch (opt) {
        default:
        case 'h':
            std::cout << "\n" << argv[0] << ":\n"
                      << "    Run with no arguments, performs a unit test.\n"
                      << "    To run a performance benchmark, specify the number of sorts to perform:\n"
                      << "        -n <number>\n"
                      << "        -v <length in bytes of varchar field>\n"
                      << "        -i <length in bytes of inline fields>\n\n";
            return 0;
        case 'n':
            NUM_SORTS = boost::lexical_cast<int>(optarg);
            break;
        case 'v':
            VARCHAR_LENGTH = boost::lexical_cast<int32_t>(optarg);
            break;
        case 'i':
            INLINE_PADDING = boost::lexical_cast<int32_t>(optarg);
            break;
        }
    }

    return TestSuite::globalInstance()->runAll();
}
