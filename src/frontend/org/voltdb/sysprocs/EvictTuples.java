package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.DependencySet;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Table;
import org.voltdb.jni.ExecutionEngine;
import org.voltdb.types.TimestampType;

import edu.brown.hstore.PartitionExecutor;
import edu.brown.profilers.AntiCacheManagerProfiler;
import edu.brown.profilers.AntiCacheManagerProfiler.EvictionHistory;

/** 
 * 
 */
@ProcInfo(
    partitionParam = 0,
    singlePartition = true
)
public class EvictTuples extends VoltSystemProcedure {
    @SuppressWarnings("unused")
    private static final Logger LOG = Logger.getLogger(EvictTuples.class);

    public static final ColumnInfo ResultsColumns[] = {
        new ColumnInfo(VoltSystemProcedure.CNAME_HOST_ID, VoltSystemProcedure.CTYPE_ID),
        new ColumnInfo("HOSTNAME", VoltType.STRING),
        new ColumnInfo("PARTITION", VoltType.INTEGER),
        new ColumnInfo("TABLE", VoltType.STRING),
        new ColumnInfo("TUPLES_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BLOCKS_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BYTES_EVICTED", VoltType.BIGINT),
        new ColumnInfo("CREATED", VoltType.TIMESTAMP),
    };
    
    @Override
    public void initImpl() {
        executor.registerPlanFragment(SysProcFragmentId.PF_antiCacheEviction, this);
    }

    @Override
    public DependencySet executePlanFragment(Long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        assert(fragmentId == SysProcFragmentId.PF_antiCacheEviction);
        throw new IllegalAccessError("Invalid invocation of " + this.getClass() + ".executePlanFragment()");
    }
    
    public VoltTable[] run(int partition, String tableNames[], long blockSizes[], int numBlocks[]) {
        ExecutionEngine ee = executor.getExecutionEngine();
        assert(tableNames.length == blockSizes.length);
        
        // PROFILER
        AntiCacheManagerProfiler profiler = null;
        long start = -1;
        if (hstore_conf.site.anticache_profiling) {
            start = System.currentTimeMillis();
            profiler = hstore_site.getAntiCacheManager().getDebugContext().getProfiler(this.partitionId);
            profiler.eviction_time.start();
        }

        // Check Input
        if (tableNames.length == 0) {
            throw new VoltAbortException("No tables to evict were given");
        }
        Table tables[] = new Table[tableNames.length];
        for (int i = 0; i < tableNames.length; i++) {
            tables[i] = catalogContext.database.getTables().getIgnoreCase(tableNames[i]);
            if (tables[i] == null) {
                String msg = String.format("Unknown table '%s'", tableNames[i]);
                throw new VoltAbortException(msg);
            }
            else if (tables[i].getEvictable() == false) {
                String msg = String.format("Trying to evict tuples from table '%s' but it is not marked as evictable", tables[i].getName());
                throw new VoltAbortException(msg);
            }
            else if (blockSizes[i] <= 0) {
                String msg = String.format("Invalid block eviction size '%d' for table '%s'", blockSizes[i], tables[i].getName());
                throw new VoltAbortException(msg);
            }
            else if(numBlocks[i] <= 0) {
                String msg = String.format("Invalid number of blocks to evict '%d' for table '%s'", numBlocks[i], tables[i].getName());
                throw new VoltAbortException(msg);
            }
        } // FOR
        
        // TODO: Instead of sending down requests one at a time per table, it will
        //       be much faster if we just send down the entire batch
        final VoltTable allResults = new VoltTable(ResultsColumns);
        long totalTuplesEvicted = 0;
        long totalBlocksEvicted = 0;
        long totalBytesEvicted = 0;
        for (int i = 0; i < tableNames.length; i++) {
            System.err.printf("Evicting %d blocks of blockSize %d", (int)numBlocks[i], blockSizes[i]);
            
            VoltTable vt = ee.antiCacheEvictBlock(tables[i], blockSizes[i], numBlocks[i]);
            boolean adv = vt.advanceRow();
            
            if(!adv) {
                String msg = String.format("antiCacheEvictBlock failed to return any rows.");
                throw new VoltAbortException(msg);
            }
//            assert(adv);
            long tuplesEvicted = vt.getLong("TUPLES_EVICTED");
            long blocksEvicted = vt.getLong("BLOCKS_EVICTED");
            long bytesEvicted = vt.getLong("BYTES_EVICTED");
            Object row[] = {
                    this.hstore_site.getSiteId(),
                    this.hstore_site.getSiteName(),
                    this.executor.getPartitionId(),
                    vt.getString("TABLE_NAME"),
                    tuplesEvicted,
                    blocksEvicted,
                    bytesEvicted,
                    new TimestampType()
            };
            allResults.addRow(row);
            totalTuplesEvicted += tuplesEvicted;
            totalBlocksEvicted += blocksEvicted;
            totalBytesEvicted += bytesEvicted;
        } // FOR
        
        // PROFILER
        if (profiler != null) {
            EvictionHistory eh = new EvictionHistory(start,
                                                     System.currentTimeMillis(),
                                                     totalTuplesEvicted,
                                                     totalBlocksEvicted,
                                                     totalBytesEvicted);
            profiler.eviction_history.add(eh);
            profiler.eviction_time.stopIfStarted();
        }
        
        return new VoltTable[]{ allResults };
    }
}
