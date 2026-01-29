package com.newcodes7.small_town.global.config;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Hibernate UserType for PostgreSQL pgvector halfvec (half-precision vector)
 * Converts between Java float[] and PostgreSQL halfvec
 *
 * halfvec uses 16-bit floats instead of 32-bit, reducing storage by 50%
 * Requires pgvector 0.7.0+
 */
public class HalfVectorType implements UserType<float[]> {

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

        if (value instanceof PGobject) {
            PGobject pgObject = (PGobject) value;
            String vectorString = pgObject.getValue();
            if (vectorString == null) {
                return null;
            }
            return parseHalfvecString(vectorString);
        }

        throw new IllegalArgumentException("Expected PGobject (halfvec) but got: " + value.getClass());
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            PGobject pgObject = new PGobject();
            pgObject.setType("halfvec");
            pgObject.setValue(arrayToString(value));
            st.setObject(index, pgObject);
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

    /**
     * Convert float array to halfvec string format: [0.1,0.2,0.3]
     */
    private String arrayToString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(array[i]);
        }
        return sb.append("]").toString();
    }

    /**
     * Parse halfvec string format: [0.1,0.2,0.3] to float array
     */
    private float[] parseHalfvecString(String str) {
        // Remove brackets
        str = str.trim();
        if (str.startsWith("[")) {
            str = str.substring(1);
        }
        if (str.endsWith("]")) {
            str = str.substring(0, str.length() - 1);
        }

        if (str.isEmpty()) {
            return new float[0];
        }

        String[] parts = str.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
