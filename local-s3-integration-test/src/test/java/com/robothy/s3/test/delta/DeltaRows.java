package com.robothy.s3.test.delta;

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import java.util.List;

/**
 * The rows that a test hands to Delta, as the columnar batch its writer takes.
 *
 * <p>Delta reads and writes data column by column, so a batch is a schema and one {@link ColumnVector} per column.
 * Only the two types these tests use are supported — {@code long} and {@code string} — which keeps this to the little
 * that the write path of {@code DeltaLakeIntegrationTest} needs; anything else fails loudly rather than quietly
 * reading as null.
 */
public final class DeltaRows {

  private DeltaRows() {
  }

  /**
   * A batch of rows.
   *
   * @param schema the schema of the rows, whose fields are {@code long} or {@code string}.
   * @param rows the values, one array per row, in the order of the fields; a {@code null} is a null value.
   * @return the batch.
   */
  public static ColumnarBatch batch(StructType schema, List<Object[]> rows) {
    return new ColumnarBatch() {

      @Override
      public StructType getSchema() {
        return schema;
      }

      @Override
      public ColumnVector getColumnVector(int ordinal) {
        return vector(schema.at(ordinal).getDataType(), rows, ordinal);
      }

      @Override
      public int getSize() {
        return rows.size();
      }
    };
  }

  private static ColumnVector vector(DataType type, List<Object[]> rows, int ordinal) {
    return new ColumnVector() {

      @Override
      public DataType getDataType() {
        return type;
      }

      @Override
      public int getSize() {
        return rows.size();
      }

      @Override
      public void close() {
      }

      @Override
      public boolean isNullAt(int rowId) {
        return rows.get(rowId)[ordinal] == null;
      }

      @Override
      public long getLong(int rowId) {
        require(type instanceof LongType, "long");
        return (Long) rows.get(rowId)[ordinal];
      }

      @Override
      public String getString(int rowId) {
        require(type instanceof StringType, "string");
        return (String) rows.get(rowId)[ordinal];
      }

      private void require(boolean isType, String name) {
        if (!isType) {
          throw new UnsupportedOperationException(
              "The rows of this test are " + name + " and long columns only; got " + type + ".");
        }
      }
    };
  }

}
