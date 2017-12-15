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
#include "common/ValueFactory.hpp"

#include "expressions/tuplevalueexpression.h"

#include "storage/LargeTempTableBlock.h"
#include "storage/LargeTempTable.h"
#include "storage/tablefactory.h"

#include "harness.h"

#include "test_utils/LargeTempTableTopend.hpp"
#include "test_utils/Tools.hpp"
#include "test_utils/UniqueEngine.hpp"
#include "test_utils/UniqueTable.hpp"

using namespace voltdb;

class LargeTempTableSortTest : public Test {
public:

    LargeTempTableSortTest()
        : m_rng(std::chrono::system_clock::now().time_since_epoch().count())
    {
    }

protected:

    UniqueTable<LargeTempTable> createAndFillLargeTempTable(int32_t varcharLengthBytes,
                                                            int32_t inlinePadding,
                                                            int32_t numBlocks) {
        LargeTempTableBlockCache* lttBlockCache = ExecutorContext::getExecutorContext()->lttBlockCache();
        TupleSchema *schema = getSchemaOfLength(varcharLengthBytes, inlinePadding);
        std::vector<std::string> names;
        names.push_back("strfld");
        for (int i = 1; i < schema->columnCount(); ++i) {
            names.push_back(std::string("tiny") + boost::lexical_cast<std::string>(i));
        }

        auto ltt = makeUniqueTable(TableFactory::buildLargeTempTable("ltmp", schema, names));

        int expectedTuples = 0;
        for (int i = 0; i < numBlocks; ++i) {
            LargeTempTableBlock* block = lttBlockCache->getEmptyBlock(schema);
            fillBlock(block);
            expectedTuples += block->activeTupleCount();
            block->unpin();
            ltt->inheritBlock(block->id());
        }

        if (expectedTuples != ltt->activeTupleCount()) {
            FAIL("Expected tuples does not match tuple count of table");
            return NULL;
        }

        return std::move(ltt);
    }

    TupleSchema* getSchemaOfLength(int32_t varcharLengthBytes, int32_t inlinePadding) {
        TupleSchemaBuilder builder(inlinePadding + 1);
        builder.setColumnAtIndex(0, VALUE_TYPE_VARCHAR, varcharLengthBytes, true, true);
        for (int i = 0; i < inlinePadding; ++i) {
            builder.setColumnAtIndex(i + 1, VALUE_TYPE_TINYINT);
        }
        return builder.build();
    }

    void fillBlock(LargeTempTableBlock* block) {
        Pool* tempPool = ExecutorContext::getTempStringPool();
        StandAloneTupleStorage storage{block->schema()};
        TableTuple tupleToInsert = storage.tuple();

        for (int i = 1; i < block->schema()->columnCount(); ++i) {
            tupleToInsert.setNValue(i, Tools::nvalueFromNative(int8_t(i)));
        }

        uint32_t varcharLength = block->schema()->getColumnInfo(0)->length;
        do {
            tupleToInsert.setNValue(0, ValueFactory::getRandomValue(VALUE_TYPE_VARCHAR, varcharLength, tempPool));
        }
        while (block->insertTuple(tupleToInsert));
    }

    private:

    std::ranlux48_base m_rng;
};

namespace {

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

bool verifySortedTable(const AbstractExecutor::TupleComparer& comparer,
                       LargeTempTable* table) {
    Pool* pool = ExecutorContext::getTempStringPool();

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
        success = lessThanOrEqual(comparer, prevTuple, verifyTuple);
        if (!success) {
            std::cerr << "Failed to verify " << tupleNum << "th tuple:\n";
            std::cerr << "    prev tuple: " << prevTuple.debug() << "\n";
            std::cerr << "    curr tuple: " << verifyTuple.debug() << std::endl;
            return false;
        }
        prevTuple.copyForPersistentInsert(verifyTuple, pool);
        ++tupleNum;
    }

    if (table->activeTupleCount() != tupleNum) {
        std::cerr << "Failed to verify table; tuple count wrong: "
                  << "expected " << table->activeTupleCount() << ", actual " << tupleNum
                  << std::endl;
        return false;
    }

    return true;
}

} // end anonymous namespace


TEST_F(LargeTempTableSortTest, sortLargeTempTable) {
    using namespace std::chrono;
    UniqueEngine engine = UniqueEngineBuilder()
        .setTopend(std::unique_ptr<LargeTempTableTopend>(new LargeTempTableTopend()))
        .build();

    // Test using different schemas, since large table blocks are
    // sorted differently depending if they have non-inlined data

    // varchar length (bytes), inline padding, number of blocks
    typedef std::tuple<int32_t, int32_t, int32_t> SortTableSpec;
#ifndef MEMCHECK
    std::vector<SortTableSpec> specs {
        SortTableSpec{16, 16, 13}, // no non-inlined data
        SortTableSpec{64, 16, 13}, // small non-inlined data
        SortTableSpec{2048, 16, 25}, // large non-inlined data
        SortTableSpec{16, 2048, 25}, // large tuples, no non-inlined data
    };
#else
    //
    std::vector<SortTableSpec> specs {
        SortTableSpec{64, 2048, 13}, // some non-inlined data
        SortTableSpec{16, 2048, 13}, // large tuples, no non-inlined data
    };
#endif

    std::cout << "\n";
    BOOST_FOREACH(auto spec, specs) {
        int32_t varcharLength = std::get<0>(spec);
        int32_t inlinePadding = std::get<1>(spec);
        int32_t numBlocks = std::get<2>(spec);

        std::cout << "            Generating " << numBlocks
                  << " blocks of tuples (VARCHAR(" << varcharLength << " BYTES), <"
                  << inlinePadding << " TINYINT fields>)...";
        std::cout.flush();

        auto ltt = createAndFillLargeTempTable(varcharLength, inlinePadding, numBlocks);

        TupleValueExpression tve{0, 0}; // table 0, field 0
        std::vector<AbstractExpression*> keys{&tve};
        std::vector<SortDirectionType> dirs{SORT_DIRECTION_TYPE_ASC};
        AbstractExecutor::TupleComparer comparer{keys, dirs};

        std::cout << "sorting...";
        std::cout.flush();

        auto startTime = high_resolution_clock::now();

        ltt->sort(comparer, -1, 0); // no limit (-1), no offset (0)

        auto endTime = high_resolution_clock::now();
        auto totalSortDurationMicros = duration_cast<microseconds>(endTime - startTime);
        std::cout << "sorted " << ltt->activeTupleCount() << " tuples in "
                  << (totalSortDurationMicros.count() / 1000000.0) << " seconds.\n";

        ASSERT_TRUE(verifySortedTable(comparer, ltt.get()));
    }

    std::cout << "            ";
}

int main(int argc, char* argv[]) {
    return TestSuite::globalInstance()->runAll();
}
