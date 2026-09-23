package com.robothy.s3.test.delta;

import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Creating a Delta table and appending rows to it with {@code delta-kernel-java}, for the tests that then read it back
 * with another client.
 *
 * <p>The write path of Delta is two steps: the Parquet data files are written first, and the actions that add them are
 * committed afterwards, so a version of the table only exists once its data does. {@code DeltaLakeIntegrationTest}
 * drives the same path itself, with the assertions on the commits that are its subject; here it is only the fixture
 * that a reader is pointed at.
 */
public final class DeltaTables {

  private DeltaTables() {
  }

  /**
   * Creates an empty table, which is version 0 of it.
   *
   * @return the version of the commit.
   */
  public static long create(Engine engine, String tablePath, StructType schema) {
    Transaction transaction = Table.forPath(engine, tablePath)
        .createTransactionBuilder(engine, "local-s3-test", Operation.CREATE_TABLE)
        .withSchema(engine, schema)
        .build(engine);
    return transaction.commit(engine, CloseableIterable.emptyIterable()).getVersion();
  }

  /**
   * Appends rows to a table, as one version of its own.
   *
   * @param rows the values, one array per row, in the order of the fields of the schema.
   * @return the version of the commit.
   */
  public static long append(Engine engine, String tablePath, StructType schema, List<Object[]> rows) {
    Transaction transaction = Table.forPath(engine, tablePath)
        .createTransactionBuilder(engine, "local-s3-test", Operation.WRITE)
        .build(engine);
    Row transactionState = transaction.getTransactionState(engine);
    CloseableIterator<FilteredColumnarBatch> data = singleton(
        new FilteredColumnarBatch(DeltaRows.batch(schema, rows), Optional.empty()));
    CloseableIterator<FilteredColumnarBatch> physicalData =
        Transaction.transformLogicalData(engine, transactionState, data, Map.of());
    DataWriteContext writeContext = Transaction.getWriteContext(engine, transactionState, Map.of());

    try {
      CloseableIterator<DataFileStatus> dataFiles = engine.getParquetHandler().writeParquetFiles(
          writeContext.getTargetDirectory(), physicalData, writeContext.getStatisticsColumns());
      CloseableIterator<Row> actions =
          Transaction.generateAppendActions(engine, transactionState, dataFiles, writeContext);
      try (CloseableIterable<Row> committed = CloseableIterable.inMemoryIterable(actions)) {
        return transaction.commit(engine, committed).getVersion();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static <T> CloseableIterator<T> singleton(T value) {
    return new CloseableIterator<>() {

      private boolean consumed;

      @Override
      public boolean hasNext() {
        return !consumed;
      }

      @Override
      public T next() {
        if (consumed) {
          throw new NoSuchElementException();
        }
        consumed = true;
        return value;
      }

      @Override
      public void close() {
      }
    };
  }

}
