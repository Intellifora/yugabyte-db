// Copyright (c) YugaByte, Inc.
package org.yb.cql;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;

import java.util.Iterator;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * This an extensive test suite ensures that we appropriately test the tricky cassandra TTL
 * semantics, with various edge cases.
 */
public class TestTTLSemantics extends TestBase {

  private void createTable(String tableName) {
    session.execute(String.format(
      "CREATE TABLE %s (k1 int, k2 int, k3 int, k4 int, c1 int, c2 " +
        "int, c3 int, PRIMARY KEY((k1, k2), k3, k4))",
      tableName));
  }

  private void assertNoRow(String tableName, int k1, int k2, int k3, int k4) {
    assertNoRow(tableName, getSelectStmt(tableName, k1, k2, k3, k4));
  }

  private String getSelectStmtWithPrimaryKey(String tableName, int k1, int k2, int k3, int k4) {
    return String.format("SELECT k1, k2, k3, k4, c1, c2, c3 FROM %s WHERE k1 = %d AND k2 " +
      "= %d AND k3 = %d and k4 = %d;", tableName, k1, k2, k3, k4);
  }

  private String getSelectStmtOnlyPrimaryKey(String tableName, int k1, int k2, int k3, int k4) {
    return String.format("SELECT k1, k2, k3, k4 FROM %s WHERE k1 = %d AND k2 " +
      "= %d AND k3 = %d and k4 = %d;", tableName, k1, k2, k3, k4);
  }

  private String getSelectStmt(String tableName, int k1, int k2, int k3, int k4) {
    return String.format("SELECT c1, c2, c3 FROM %s WHERE k1 = %d AND k2 " +
      "= %d AND k3 = %d and k4 = %d;", tableName, k1, k2, k3, k4);
  }

  private Row getFirstRow(String tableName, int k1, int k2, int k3, int k4) {
    ResultSet rs = session.execute(getSelectStmt(tableName, k1, k2, k3, k4));
    Iterator<Row> iter = rs.iterator();
    assertTrue(iter.hasNext());
    return iter.next();
  }

  private Row getFirstRowWithPrimaryKey(String tableName, int k1, int k2, int k3, int k4) {
    ResultSet rs = session.execute(getSelectStmtWithPrimaryKey(tableName, k1, k2, k3, k4));
    Iterator<Row> iter = rs.iterator();
    assertTrue(iter.hasNext());
    return iter.next();
  }

  private Row getFirstRowOnlyPrimaryKey(String tableName, int k1, int k2, int k3, int k4) {
    ResultSet rs = session.execute(getSelectStmtOnlyPrimaryKey(tableName, k1, k2, k3, k4));
    Iterator<Row> iter = rs.iterator();
    assertTrue(iter.hasNext());
    return iter.next();
  }

  private void verifyPrimaryKey(Row row, int k1, int k2, int k3, int k4) {
    assertEquals(k1, row.getInt(0));
    assertEquals(k2, row.getInt(1));
    assertEquals(k3, row.getInt(2));
    assertEquals(k4, row.getInt(3));
  }

  @Test
  public void testUpdateAsUpsert() throws Exception {
    String tableName = "testUpdateAsUpsert";
    createTable(tableName);
    session.execute(String.format("UPDATE %s USING TTL 1 SET c1 = 1, c2 = 2, c3 = 3 WHERE k1 = 1 " +
      "AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    // Verify row exists.
    Row row = getFirstRow(tableName, 1, 2, 3, 4);
    assertEquals(1, row.getInt(0));
    assertEquals(2, row.getInt(1));
    assertEquals(3, row.getInt(2));

    Thread.sleep(900);

    // Verify row still exists.
    row = getFirstRow(tableName, 1, 2, 3, 4);
    assertEquals(1, row.getInt(0));
    assertEquals(2, row.getInt(1));
    assertEquals(3, row.getInt(2));

    // Wait for ttl expiry.
    Thread.sleep(150);

    // Now verify the row is completely gone.
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testRowTTLWithUpdates() throws Exception {
    String tableName = "testRowTTLWithUpdates";
    createTable(tableName);

    // Insert with a TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1, 2, 3) USING TTL 2", tableName));

    // Update TTL for the columns.
    session.execute(String.format("UPDATE %s USING TTL 1 SET c1 = 100, c2 = 200, c3 = 300 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Verify columns expire, primary key lives on.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertTrue(row.isNull(4));
    assertTrue(row.isNull(5));
    assertTrue(row.isNull(6));

    Thread.sleep(1000);

    // Verify row expires.
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testUpdateRowTTL() throws Exception {
    String tableName = "testUpdateRowTTL";
    createTable(tableName);

    // Insert with a TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1, 2, 3) USING TTL 1", tableName));

    // Now increase the ttl for the columns.
    session.execute(String.format("UPDATE %s USING TTL 2 SET c1 = 100 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));
    session.execute(String.format("UPDATE %s USING TTL 3 SET c2 = 200 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));
    session.execute(String.format("UPDATE %s USING TTL 4 SET c3 = 300 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Verify whole row is present.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertEquals(100, row.getInt(4));
    assertEquals(200, row.getInt(5));
    assertEquals(300, row.getInt(6));

    Thread.sleep(1000);

    // Verify c1 is gone.
    row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertTrue(row.isNull(4));
    assertEquals(200, row.getInt(5));
    assertEquals(300, row.getInt(6));

    Thread.sleep(1000);

    // Verify c2 is gone.
    row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertTrue(row.isNull(4));
    assertTrue(row.isNull(5));
    assertEquals(300, row.getInt(6));

    Thread.sleep(1000);

    // Verify whole row is now gone.
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testDecreaseRowTTL() throws Exception {
    String tableName = "testDecreaseRowTTL";
    createTable(tableName);

    // Insert with a TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1, 2, 3) USING TTL 60", tableName));

    // Reduce the column's TTL with an update
    session.execute(String.format("UPDATE %s USING TTL 1 SET c1 = 100, c2 = 200, c3 = 300 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Verify all columns are gone, but primary key survives.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertTrue(row.isNull(4));
    assertTrue(row.isNull(5));
    assertTrue(row.isNull(6));
  }

  @Test
  public void testRowSurvivesWithSingleColumn() throws Exception {
    String tableName = "testRowSurvivesWithSingleColumn";
    createTable(tableName);

    // Insert with a TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1, 2, 3) USING TTL 1", tableName));

    // Overwrite a single column.
    session.execute(String.format("UPDATE %s SET c1 = 100 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Verify row survives with single column.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertEquals(100, row.getInt(4));
    assertTrue(row.isNull(5));
    assertTrue(row.isNull(6));
  }

  @Test
  public void testTTLWithOverwrites() throws Exception {
    String tableName = "testTTLWithOverwrites";
    createTable(tableName);

    // Insert with a TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1, 2, 3) USING TTL 2", tableName));

    // Overwrite with new TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 100, 200, 300) USING TTL 1", tableName));

    Thread.sleep(1050);

    // Verify whole row is gone due to new TTL.
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testInsertUpdateWithNull() throws Exception {
    String tableName = "testInsertUpdateWithNull";
    createTable(tableName);

    // Insert with null columns.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, null, null, null)", tableName));

    // Verify we have a primary key.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertTrue(row.isNull(4));
    assertTrue(row.isNull(5));
    assertTrue(row.isNull(6));

    // Update with null columns.
    session.execute(String.format("UPDATE %s SET c1 = null, c2 = null, c3 = null WHERE k1 = 100 " +
      "AND k2 = 200 AND k3 = 300 AND k4 = 400", tableName));

    // Verify whole row is gone.
    assertNoRow(tableName, 100, 200, 300, 400);

    // Add a row with valid columns.
    session.execute(String.format("UPDATE %s SET c1 = 100, c2 = 200, c3 = 300 WHERE k1 = 10 " +
      "AND k2 = 20 AND k3 = 30 AND k4 = 40", tableName));

    // Verify row exists.
    row = getFirstRowWithPrimaryKey(tableName, 10, 20, 30, 40);
    verifyPrimaryKey(row, 10, 20, 30, 40);
    assertEquals(100, row.getInt(4));
    assertEquals(200, row.getInt(5));
    assertEquals(300, row.getInt(6));

    // Now set all columns to null and row is gone!
    session.execute(String.format("UPDATE %s SET c1 = null, c2 = null, c3 = null WHERE k1 = 10 " +
      "AND k2 = 20 AND k3 = 30 AND k4 = 40", tableName));
    assertNoRow(tableName, 10, 20, 30, 40);
  }

  @Test
  public void testWithDeletes() throws Exception {
    String tableName = "testWithDeletes";
    createTable(tableName);

    // Insert a row with TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 10, 20, 30) USING TTL 60", tableName));

    // Delete the row.
    session.execute(String.format("DELETE FROM %s WHERE k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4",
      tableName));

    // Insert with a new TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 100, 200, 300) USING TTL 2", tableName));

    // Now overwrite the row again with a newer TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 1000, 2000, 3000) USING TTL 1", tableName));

    // Update a single column with new TTL.
    session.execute(String.format("UPDATE %s USING TTL 2 SET c1 = 1001 WHERE k1 = 1 " +
      "AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    // Verify row exists.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertEquals(1001, row.getInt(4));
    assertEquals(2000, row.getInt(5));
    assertEquals(3000, row.getInt(6));

    Thread.sleep(1050);

    // One column and primary key survive.
    row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertEquals(1001, row.getInt(4));
    assertTrue(row.isNull(5));
    assertTrue(row.isNull(6));

    Thread.sleep(1000);

    // Now whole row is gone.
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testLivenessColumnExpiry() throws Exception {
    String tableName = "testLivenessColumnExpiry";
    createTable(tableName);

    // Insert a row with TTL.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 10, 20, 30) USING TTL 1", tableName));

    // Overwrite all columns.
    session.execute(String.format("UPDATE %s SET c1 = 100, c2 = 200, c3 = 300 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Liveness column has expired, but whole row still exists.
    Row row = getFirstRowWithPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);
    assertEquals(100, row.getInt(4));
    assertEquals(200, row.getInt(5));
    assertEquals(300, row.getInt(6));

    // Verify selecting only primary keys works too.
    row = getFirstRowOnlyPrimaryKey(tableName, 1, 2, 3, 4);
    verifyPrimaryKey(row, 1, 2, 3, 4);

    // Now the row behaves like a row created with an UPDATE and hence setting all columns to
    // null, deletes the whole row.
    session.execute(String.format("UPDATE %s SET c1 = null, c2 = null, c3 = null WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));
    assertNoRow(tableName, 1, 2, 3, 4);
  }

  @Test
  public void testNullColumns() throws Exception {
    String tableName = "testNullcolumns";
    createTable(tableName);

    // Insert a couple of rows.
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (1, 2, 3, " +
      "4, 10, 20, 30)", tableName));
    session.execute(String.format("INSERT INTO %s (k1, k2, k3, k4, c1, c2, c3) VALUES (2, 2, 3, " +
      "4, 100, 200, 300)", tableName));

    // Update the ttl of c1.
    session.execute(String.format("UPDATE %s USING TTL 1 SET c1 = 100 WHERE " +
      "k1 = 1 AND k2 = 2 AND k3 = 3 AND k4 = 4", tableName));

    Thread.sleep(1050);

    // Verify correct set of rows.
    ResultSet rs = session.execute(String.format("SELECT c1 FROM %s WHERE k1 = 1 AND k2 = 2 AND " +
      "k3 = 3 and k4 = 4;", tableName));
    Iterator<Row> iter = rs.iterator();
    assertTrue(iter.hasNext());
    Row row = iter.next();
    assertTrue(row.isNull(0));

    // Now retrieve second row to verify it exists.
    rs = session.execute(String.format("SELECT c1 FROM %s WHERE k1 = 2 AND k2 = 2 AND " +
      "k3 = 3 and k4 = 4;", tableName));
    iter = rs.iterator();
    assertTrue(iter.hasNext());
    row = iter.next();
    assertEquals(100, row.getInt(0));
  }
}