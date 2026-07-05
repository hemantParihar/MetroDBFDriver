package com.dbf.jdbc.optimizer.stats;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.NDXIndex;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Collects table and index statistics for cost-based optimization
 */
public class StatisticsCollector {
    private final String basePath;
    private final Map<String, TableStats> tableStats = new HashMap<>();
    
    public static class TableStats {
        public final String tableName;
        public long rowCount;
        public int columnCount;
        public Map<String, ColumnStats> columnStats = new HashMap<>();
        public Map<String, IndexStats> indexes = new HashMap<>();
        
        public TableStats(String tableName) {
            this.tableName = tableName;
        }
    }
    
    public static class ColumnStats {
        public final String columnName;
        public int distinctValues;
        public Object minValue;
        public Object maxValue;
        public int nullCount;
        
        public ColumnStats(String columnName) {
            this.columnName = columnName;
            this.distinctValues = 0;
            this.nullCount = 0;
        }
        
        public double getSelectivity(Object value) {
            if (distinctValues == 0) return 1.0;
            return 1.0 / distinctValues;
        }
    }
    
    public static class IndexStats {
        public final String indexName;
        public final String columnName;
        public final boolean isUnique;
        public final long entryCount;
        
        public IndexStats(String indexName, String columnName, boolean isUnique, long entryCount) {
            this.indexName = indexName;
            this.columnName = columnName;
            this.isUnique = isUnique;
            this.entryCount = entryCount;
        }
    }
    
    public StatisticsCollector(String basePath) {
        this.basePath = basePath;
    }
    
    public TableStats getTableStats(String tableName) throws IOException {
        TableStats stats = tableStats.get(tableName.toLowerCase());
        if (stats == null) {
            stats = collectTableStats(tableName);
            tableStats.put(tableName.toLowerCase(), stats);
        }
        return stats;
    }
    
    private TableStats collectTableStats(String tableName) throws IOException {
        TableStats stats = new TableStats(tableName);
        String filePath = basePath + "/" + tableName + ".dbf";
        File file = new File(filePath);
        
        if (!file.exists()) {
            return stats;
        }
        
        try (DBFReader reader = new DBFReader(filePath, java.nio.charset.Charset.forName("UTF-8"))) {
            stats.rowCount = reader.getHeader().getRecordCount();
            stats.columnCount = reader.getHeader().getFieldCount();
            
            // Initialize column stats
            for (int i = 0; i < stats.columnCount; i++) {
                String colName = reader.getHeader().getField(i).getName();
                stats.columnStats.put(colName.toLowerCase(), new ColumnStats(colName));
            }
            
            // Sample rows for column statistics (sample 1% but max 10000)
            long sampleSize = Math.min(10000, stats.rowCount / 100);
            if (sampleSize < 100 && stats.rowCount > 0) sampleSize = Math.min(stats.rowCount, 1000);
            
            if (sampleSize > 0) {
                reader.beforeFirst();
                long sampled = 0;
                Map<Integer, Map<Object, Integer>> valueCounts = new HashMap<>();
                
                while (reader.next() && sampled < sampleSize) {
                    for (int i = 0; i < stats.columnCount; i++) {
                        Object value = null;
						try {
							value = reader.getValue(i);
						} catch (SQLException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
                        if (value != null) {
                            valueCounts.computeIfAbsent(i, k -> new HashMap<>())
                                      .merge(value, 1, Integer::sum);
                        }
                    }
                    sampled++;
                }
                
                // Build column stats from samples
                for (int i = 0; i < stats.columnCount; i++) {
                    String colName = reader.getHeader().getField(i).getName();
                    ColumnStats colStats = stats.columnStats.get(colName.toLowerCase());
                    Map<Object, Integer> counts = valueCounts.getOrDefault(i, new HashMap<>());
                    colStats.distinctValues = counts.size();
                }
            }
        }
        
        // Check for NDX index
        File ndxFile = new File(basePath + "/" + tableName + ".ndx");
        if (ndxFile.exists()) {
            try {
                NDXIndex index = new NDXIndex(ndxFile.getPath());
                index.load();
                String columnName = index.getColumnName();
                if (columnName != null && !columnName.isEmpty()) {
                    stats.indexes.put(columnName.toLowerCase(), 
                        new IndexStats(tableName + ".ndx", columnName, false, stats.rowCount));
                } else {
                    // If we can't determine column name, use a generic key
                    stats.indexes.put("__primary__", 
                        new IndexStats(tableName + ".ndx", "__primary__", false, stats.rowCount));
                }
            } catch (Exception e) {
                // Ignore - index might be corrupted or unsupported
                System.err.println("Warning: Could not load NDX index for " + tableName + ": " + e.getMessage());
            }
        }
        
        // Check for MDX index (multiple indexes in one file)
        File mdxFile = new File(basePath + "/" + tableName + ".mdx");
        if (mdxFile.exists()) {
            try {
                com.dbf.jdbc.index.MDXIndex mdxIndex = new com.dbf.jdbc.index.MDXIndex(mdxFile.getPath());
                for (String tagName : mdxIndex.getTagNames()) {
                    stats.indexes.put(tagName.toLowerCase(), 
                        new IndexStats(tableName + ".mdx", tagName, false, stats.rowCount));
                }
            } catch (Exception e) {
                // Ignore
                System.err.println("Warning: Could not load MDX index for " + tableName + ": " + e.getMessage());
            }
        }
        
        return stats;
    }
    
    public void printStats(String tableName) throws IOException {
        TableStats stats = getTableStats(tableName);
        System.out.println("=== Statistics for " + tableName + " ===");
        System.out.println("Row count: " + stats.rowCount);
        System.out.println("Column count: " + stats.columnCount);
        System.out.println("Indexes: " + stats.indexes.keySet());
        System.out.println("Column stats:");
        for (ColumnStats colStats : stats.columnStats.values()) {
            System.out.println("  " + colStats.columnName + ": distinct=" + colStats.distinctValues);
        }
    }
 // Add to StatisticsCollector.java

    public boolean hasIndexOnColumn(String tableName, String columnName) throws IOException {
        TableStats stats = getTableStats(tableName);
        return stats.indexes.containsKey(columnName.toLowerCase());
    }

    public String getIndexForColumn(String tableName, String columnName) throws IOException {
        TableStats stats = getTableStats(tableName);
        IndexStats idxStats = stats.indexes.get(columnName.toLowerCase());
        return idxStats != null ? idxStats.indexName : null;
    }

    public boolean isSelectiveIndex(String tableName, String columnName, Object value) throws IOException {
        TableStats stats = getTableStats(tableName);
        ColumnStats colStats = stats.columnStats.get(columnName.toLowerCase());
        
        if (colStats == null) return false;
        
        // Index is selective if distinct values are high
        // More than 1% distinct values is considered selective
        double selectivity = colStats.distinctValues / (double) stats.rowCount;
        return selectivity > 0.01;
    }
}