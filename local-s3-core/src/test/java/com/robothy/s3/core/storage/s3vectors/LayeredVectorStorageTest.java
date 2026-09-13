package com.robothy.s3.core.storage.s3vectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayeredVectorStorageTest {

  @TempDir
  Path tempDir;

  @Test
  void storesInTheFrontAndReadsTheBack() {
    Long fromBack = VectorStorage.createFileSystem(tempDir, 10).putVectorData(new float[] {1.0f, 2.0f});
    VectorStorage storage = VectorStorage.createLayered(VectorStorage.createInMemory(),
        VectorStorage.createReadOnlyFileSystem(tempDir, 10));

    Long fromFront = storage.putVectorData(new float[] {3.0f, 4.0f});

    assertArrayEquals(new float[] {1.0f, 2.0f}, storage.getVectorData(fromBack));
    assertArrayEquals(new float[] {3.0f, 4.0f}, storage.getVectorData(fromFront));
    assertTrue(storage.vectorDataExists(fromBack));
    assertEquals(8, storage.getVectorDataSize(fromBack));
    assertEquals(2, storage.getStoredVectorCount());
    assertFalse(Files.exists(tempDir.resolve(String.valueOf(fromFront))), "A new vector isn't written to the back.");
  }

  @Test
  void deletingAVectorOfTheBackHidesIt() {
    VectorStorage back = VectorStorage.createFileSystem(tempDir, 10);
    Long fromBack = back.putVectorData(new float[] {1.0f, 2.0f});
    VectorStorage storage = VectorStorage.createLayered(VectorStorage.createInMemory(),
        VectorStorage.createReadOnlyFileSystem(tempDir, 10));
    Long fromFront = storage.putVectorData(new float[] {3.0f, 4.0f});

    assertTrue(storage.deleteVectorData(fromBack));
    assertTrue(storage.deleteVectorData(fromFront));

    assertNull(storage.getVectorData(fromBack));
    assertFalse(storage.vectorDataExists(fromBack));
    assertEquals(-1, storage.getVectorDataSize(fromBack));
    assertNull(storage.getVectorData(fromFront));
    assertEquals(0, storage.getStoredVectorCount());
    assertFalse(storage.deleteVectorData(fromBack), "A hidden vector is deleted once.");
    assertFalse(storage.deleteVectorData(999L));
    assertFalse(storage.deleteVectorData(null));

    assertArrayEquals(new float[] {1.0f, 2.0f}, back.getVectorData(fromBack), "The back is unchanged.");
  }

}
