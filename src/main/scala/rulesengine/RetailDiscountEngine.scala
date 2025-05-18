package rulesengine

import java.io.FileWriter
import java.time.{LocalDate, LocalDateTime, Period}
import java.time.format.DateTimeFormatter
import java.sql.{Connection, DriverManager}
import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
 * RetailDiscountEngine
 *
 * Processes retail transactions by applying multiple discount rules,
 * calculating final discounted prices, logging activities, and storing
 * results in a PostgreSQL database.
 *
 * Main features:
 * - Read transactions from CSV
 * - Apply business discount rules
 * - Log processing details
 * - Write results to DB
 */
object RetailDiscountEngine {

  private val DB_URL = "jdbc:postgresql://localhost:5432/postgres"
  private val DB_USER = "user"
  private val DB_PASSWORD = "password"
  private val DB_TABLE = "discounted_transactions"

  private val LOG_FILE_PATH = "/Users/mohamedmoaaz/IdeaProjects/retail-discout-engine/src/main/scala/result/rules_engine.log"
  private val INPUT_CSV_PATH = "/Users/mohamedmoaaz/IdeaProjects/retail-discout-engine/src/main/scala/resources/TRX1000.csv"

  private val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
  private val dateOnlyFormatter = DateTimeFormatter.ISO_DATE

  // --------------------------
  // Data Models
  // --------------------------

  /**
   * Represents a retail transaction.
   *
   * @param timestamp Date and time of the transaction
   * @param productName Name of the product
   * @param expiryDate Expiry date of the product
   * @param quantity Number of units purchased
   * @param unitPrice Price per unit
   * @param channel Sales channel (e.g., app, store)
   * @param paymentMethod Payment method used (e.g., visa)
   */
  private case class Transaction(
                                  timestamp: LocalDateTime,
                                  productName: String,
                                  expiryDate: LocalDate,
                                  quantity: Int,
                                  unitPrice: Double,
                                  channel: String,
                                  paymentMethod: String
                                )

  /**
   * Represents a transaction with discount calculations.
   *
   * @param originalTransaction The original transaction data
   * @param applicableDiscounts List of discounts applied
   * @param finalDiscount Final discount percentage applied
   * @param finalPrice Price after discount
   */
  private case class DiscountedTransaction(
                                            originalTransaction: Transaction,
                                            applicableDiscounts: List[Double],
                                            finalDiscount: Double,
                                            finalPrice: Double
                                          )

  /**
   * Represents a log entry for recording processing events.
   *
   * @param timestamp Timestamp of the log event
   * @param logLevel Log severity level (e.g., INFO, ERROR)
   * @param message Log message content
   */
  private case class LogEntry(
                               timestamp: LocalDateTime,
                               logLevel: String,
                               message: String
                             )

  // --------------------------
  // Database Operations
  // --------------------------

  /**
   * Executes a block of code with a JDBC connection to the configured PostgreSQL database.
   *
   * Ensures the connection is closed after use.
   *
   * @param block The code block to execute using the database connection
   * @tparam T The return type of the block
   * @return The result of the code block
   */
  private def withDatabaseConnection[T](block: Connection => T): T = {
    Class.forName("org.postgresql.Driver")
    val connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)
    try {
      block(connection)
    } finally {
      connection.close()
    }
  }

  /**
   * Batch inserts a list of discounted transactions into the configured database table.
   *
   * @param results List of DiscountedTransaction to be saved
   */
  private def writeResultsToDatabase(results: List[DiscountedTransaction]): Unit = {
    withDatabaseConnection { connection =>
      val insertSQL =
        s"""
           |INSERT INTO $DB_TABLE (
           |    transaction_timestamp,
           |    product_name,
           |    expiry_date,
           |    quantity,
           |    unit_price,
           |    channel,
           |    payment_method,
           |    applicable_discounts,
           |    final_discount,
           |    final_price,
           |    processing_timestamp
           |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           |""".stripMargin

      val preparedStatement = connection.prepareStatement(insertSQL)

      results.foreach { result =>
        val original = result.originalTransaction
        preparedStatement.setTimestamp(1, java.sql.Timestamp.valueOf(original.timestamp))
        preparedStatement.setString(2, original.productName)
        preparedStatement.setDate(3, java.sql.Date.valueOf(original.expiryDate))
        preparedStatement.setInt(4, original.quantity)
        preparedStatement.setDouble(5, original.unitPrice)
        preparedStatement.setString(6, original.channel)
        preparedStatement.setString(7, original.paymentMethod)
        preparedStatement.setString(8, result.applicableDiscounts.mkString(";"))
        preparedStatement.setDouble(9, result.finalDiscount)
        preparedStatement.setDouble(10, result.finalPrice)
        preparedStatement.setTimestamp(11, java.sql.Timestamp.valueOf(LocalDateTime.now()))

        preparedStatement.addBatch()
      }

      preparedStatement.executeBatch()
    }
  }

  // --------------------------
  // Discount Calculation Logic
  // --------------------------

  /**
   * Calculates the final discount percentage from a list of applicable discounts.
   *
   * Logic:
   * - If no discounts, return 0
   * - If one discount, return it
   * - If multiple discounts, average the top two highest discounts
   *
   * @param discounts List of discount percentages
   * @return Final discount percentage to apply
   */
  private def calculateFinalDiscount(discounts: List[Double]): Double = discounts match {
    case Nil => 0.0
    case d if d.length == 1 => d.head
    case d => d.sorted.takeRight(2).sum / 2
  }

  /**
   * Calculates the final price of a transaction after applying the discount.
   *
   * @param transaction The original transaction
   * @param discount The discount percentage to apply
   * @return The discounted total price
   */
  private def calculateFinalPrice(transaction: Transaction, discount: Double): Double = {
    val total = transaction.unitPrice * transaction.quantity
    total - (total * discount / 100)
  }

  // --------------------------
  // Discount Rules
  // --------------------------

  /**
   * Computes the number of days remaining until the product's expiry date from the transaction timestamp.
   *
   * @param transaction The transaction to check
   * @return Number of days remaining
   */
  private def daysRemaining(transaction: Transaction): Int = {
    Period.between(transaction.timestamp.toLocalDate, transaction.expiryDate).getDays
  }

  /**
   * Applies expiry date based discount:
   * - If expiry date is within 30 days but not expired, discount = (30 - days remaining)
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def expiryDiscount(transaction: Transaction): Option[Double] = {
    val days = daysRemaining(transaction)
    if (days < 30 && days > 0) Some((30 - days).toDouble) else None
  }

  /**
   * Applies product type discounts based on product name.
   * E.g., cheese 10%, wine 5%.
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def productTypeDiscount(transaction: Transaction): Option[Double] = {
    transaction.productName.toLowerCase match {
      case p if p.contains("cheese") => Some(10.0)
      case p if p.contains("wine") => Some(5.0)
      case _ => None
    }
  }

  /**
   * Applies a special date discount if the transaction occurred on March 23rd.
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def specialDateDiscount(transaction: Transaction): Option[Double] = {
    if (transaction.timestamp.getMonthValue == 3 && transaction.timestamp.getDayOfMonth == 23) {
      Some(50.0)
    } else None
  }

  /**
   * Applies discount based on quantity ranges.
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def quantityDiscount(transaction: Transaction): Option[Double] = {
    transaction.quantity match {
      case q if q >= 6 && q <= 9 => Some(5.0)
      case q if q >= 10 && q <= 14 => Some(7.0)
      case q if q >= 15 => Some(10.0)
      case _ => None
    }
  }

  /**
   * Applies bulk purchase discount if quantity > 5.
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def bulkDiscount(transaction: Transaction): Option[Double] = {
    if (transaction.quantity > 5) Some(5.0) else None
  }

  /**
   * Applies discount if purchase channel is 'app'.
   * Gives 5% for every 5 items (rounded up).
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def appUsageDiscount(transaction: Transaction): Option[Double] = {
    if (transaction.channel.equalsIgnoreCase("app")) {
      val multiples = (transaction.quantity + 4) / 5
      Some(multiples * 5.0)
    } else {
      None
    }
  }

  /**
   * Applies a 5% discount if the payment method is Visa.
   *
   * @param transaction The transaction to evaluate
   * @return Optional discount percentage
   */
  private def visaCardDiscount(transaction: Transaction): Option[Double] = {
    if (transaction.paymentMethod.equalsIgnoreCase("visa")) {
      Some(5.0)
    } else {
      None
    }
  }

  /**
   * Aggregates all applicable discounts for a transaction by calling individual discount rules.
   *
   * @param transaction The transaction to evaluate
   * @return List of applicable discount percentages
   */
  private def getAllDiscounts(transaction: Transaction): List[Double] = {
    List(
      expiryDiscount(transaction),
      productTypeDiscount(transaction),
      specialDateDiscount(transaction),
      quantityDiscount(transaction),
      bulkDiscount(transaction),
      appUsageDiscount(transaction),
      visaCardDiscount(transaction)
    ).flatten
  }

  // --------------------------
  // Transaction Processing
  // --------------------------

  /**
   * Processes a transaction by applying all discount rules,
   * calculating final discount and price.
   *
   * @param transaction The transaction to process
   * @return DiscountedTransaction containing original data and discount results
   */
  private def processTransaction(transaction: Transaction): DiscountedTransaction = {
    val discounts = getAllDiscounts(transaction)
    val finalDiscount = calculateFinalDiscount(discounts)
    val finalPrice = calculateFinalPrice(transaction, finalDiscount)

    DiscountedTransaction(transaction, discounts, finalDiscount, finalPrice)
  }

  /**
   * Creates a log entry describing the result of processing a transaction.
   *
   * @param transaction The original transaction
   * @param result The discounted transaction result
   * @return A LogEntry describing the processing outcome
   */
  private def createLogEntry(transaction: Transaction, result: DiscountedTransaction): LogEntry = {
    val message = s"Processed transaction for ${transaction.productName}. " +
      s"Applied discounts: ${result.applicableDiscounts.mkString(", ")}%. " +
      s"Final discount: ${result.finalDiscount}%. " +
      s"Final price: ${result.finalPrice}"

    LogEntry(LocalDateTime.now(), "INFO", message)
  }

  // --------------------------
  // I/O Operations
  // --------------------------

  /**
   * Parses a CSV line into a Transaction object.
   *
   * @param line CSV formatted string representing a transaction
   * @return Parsed Transaction object
   */
  private def mapLineToTransaction(line: String): Transaction = {
    val cols = line.split(",").map(_.trim)
    Transaction(
      timestamp = LocalDateTime.parse(cols(0), dateFormatter),
      productName = cols(1),
      expiryDate = LocalDate.parse(cols(2), dateOnlyFormatter),
      quantity = cols(3).toInt,
      unitPrice = cols(4).toDouble,
      channel = cols(5),
      paymentMethod = cols(6)
    )
  }

  /**
   * Parses a list of CSV lines into a list of Transaction objects.
   * Logs an error and returns empty list if parsing fails.
   *
   * @param lines List of CSV lines (excluding header)
   * @return List of parsed Transaction objects
   */
  private def parseTransactions(lines: List[String]): List[Transaction] = {
    Try {
      lines.map(mapLineToTransaction)
    } match {
      case Success(transactions) => transactions
      case Failure(e) =>
        logError("Transaction parsing failed", e)
        List.empty[Transaction]
    }
  }

  /**
   * Reads lines from a CSV file, skipping the header.
   *
   * @param filePath Path to the CSV file
   * @return List of CSV lines as strings
   */
  private def readTransactions(filePath: String): List[String] = {
    val source = Source.fromFile(filePath)
    try {
      source.getLines().drop(1).toList
    } finally {
      source.close()
    }
  }

  /**
   * Appends a log entry to the log file.
   *
   * @param logEntry LogEntry to append
   */
  private def appendToLogFile(logEntry: LogEntry): Unit = {
    val writer = new FileWriter(LOG_FILE_PATH, true)
    try {
      writer.write(s"${logEntry.timestamp}   ${logEntry.logLevel}   ${logEntry.message}\n")
    } finally {
      writer.close()
    }
  }

  /**
   * Logs an error message and stack trace to the log file.
   *
   * @param message Error message
   * @param exception Throwable error object
   */
  private def logError(message: String, exception: Throwable): Unit = {
    appendToLogFile(LogEntry(
      LocalDateTime.now(),
      "ERROR",
      s"$message: ${exception.getMessage}\n${exception.getStackTrace.mkString("\n")}"
    ))
  }

  // --------------------------
  // Main Application Entry Point
  // --------------------------

  /**
   * Main application entry point.
   *
   * Reads transactions from CSV, processes them, logs results,
   * and writes discounted transactions to the database.
   *
   * @param args Command line arguments (unused)
   */
  def main(args: Array[String]): Unit = {
    val transactionLines = readTransactions(INPUT_CSV_PATH)
    val transactions = parseTransactions(transactionLines)

    val results = transactions.map { transaction =>
      val result = processTransaction(transaction)
      appendToLogFile(createLogEntry(transaction, result))
      result
    }

    writeResultsToDatabase(results)
    println("Processing complete. Results written to database.")
  }
}