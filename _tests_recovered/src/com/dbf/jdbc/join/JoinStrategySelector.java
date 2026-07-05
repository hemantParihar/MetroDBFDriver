package com.dbf.jdbc.join;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.index.NDXIndex;
import java.io.IOException;

/**
 * Automatically selects best join strategy based on:
 * - Table sizes
 * - Available indexes
 * - Data distribution
 */
public class JoinStrategySelector {
    
    public enum Strategy {
        HASH_JOIN,      // Best for equi-join with small build side
        INDEX_NESTED,   // Best when inner table has index
        SORT_MERGE,     // Best for large sorted datasets
        NESTED_LOOP     // Fallback for small tables
    }
    
    public static JoinStrategy selectStrategy(long leftSize, long rightSize, 
                                                boolean hasIndex, boolean isSorted) {
        if (hasIndex) {
            return new JoinStrategy(Strategy.INDEX_NESTED, 
                "Using index nested loop join - O(n log m)", 
                Math.min(leftSize, rightSize) * (long) Math.log(rightSize));
        }
        
        if (isSorted) {
            return new JoinStrategy(Strategy.SORT_MERGE,
                "Using sort merge join - O(n log n + m log m)",
                (long)(leftSize * Math.log(leftSize) + rightSize * Math.log(rightSize)));
        }
        
        long smaller = Math.min(leftSize, rightSize);
        if (smaller < 10000) {
            return new JoinStrategy(Strategy.HASH_JOIN,
                "Using hash join - O(n + m) with memory: " + (smaller * 100) + " bytes",
                leftSize + rightSize);
        }
        
        return new JoinStrategy(Strategy.NESTED_LOOP,
            "Using nested loop join - O(n * m) WARNING: may be slow for large tables",
            leftSize * rightSize);
    }
    
    public static class JoinStrategy {
        public final Strategy strategy;
        public final String description;
        public final long estimatedCost;
        
        JoinStrategy(Strategy strategy, String description, long estimatedCost) {
            this.strategy = strategy;
            this.description = description;
            this.estimatedCost = estimatedCost;
        }
    }
}