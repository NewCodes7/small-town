package com.newcodes7.small_town.global.config;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;
import com.pgvector.PGvector;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Hibernate UserType for PostgreSQL pgvector
 * Converts between Java float[] and PostgreSQL vector(1536)
 */
public class VectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) throws HibernateException {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) throws HibernateException {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        Object value = rs.getObject(position);
        if (value == null) {
            return null;
        }

        if (value instanceof PGvector) {
            return ((PGvector) value).toArray();
        }

        // PostgreSQL JDBC driver returns PGobject for vector type
        if (value instanceof PGobject) {
            PGobject pgObject = (PGobject) value;
            String vectorString = pgObject.getValue();
            if (vectorString == null) {
                return null;
            }
            // Convert PGobject to PGvector and then to float array
            PGvector vector = new PGvector(vectorString);
            return vector.toArray();
        }

        throw new IllegalArgumentException("Expected PGvector or PGobject but got: " + value.getClass());
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            PGvector vector = new PGvector(value);
            st.setObject(index, vector);
        }
    }

    @Override
    public float[] deepCopy(float[] value) throws HibernateException {
        if (value == null) {
            return null;
        }
        return Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) throws HibernateException {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) throws HibernateException {
        return deepCopy((float[]) cached);
    }

    @Override
    public float[] replace(float[] detached, float[] managed, Object owner) throws HibernateException {
        return deepCopy(detached);
    }
}
