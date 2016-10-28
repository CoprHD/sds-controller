package com.emc.storageos.db.client.impl;

import com.netflix.astyanax.serializers.AnnotatedCompositeSerializer;

/**
 * Index column serializer
 */
public class IndexColumnNameSerializer2 extends AnnotatedCompositeSerializer<IndexColumnName2> {
    private static final IndexColumnNameSerializer2 instance = new IndexColumnNameSerializer2();
    private static final String COMPARATOR_NAME =
            "org.apache.cassandra.db.marshal.CompositeType(org.apache.cassandra.db.marshal.TimeUUIDType,"+
                    "org.apache.cassandra.db.marshal.UTF8Type," +
                    "org.apache.cassandra.db.marshal.UTF8Type," +
                    "org.apache.cassandra.db.marshal.UTF8Type," +
                    "org.apache.cassandra.db.marshal.UTF8Type)";

    public static IndexColumnNameSerializer2 get() {
        return instance;
    }

    public static String getComparatorName() {
        return COMPARATOR_NAME;
    }

    public IndexColumnNameSerializer2() {
        super(IndexColumnName2.class);
    }
}