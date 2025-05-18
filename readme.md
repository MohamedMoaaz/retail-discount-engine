# 🛒 Retail Discount Engine 💸

A **Scala-based** retail transaction discount rules engine that reads transactions from CSV, applies multiple discount rules, calculates final prices, logs processing details, and stores results in a PostgreSQL database.

---

## ✨ Features

- 📄 Parse retail transaction data from CSV files  
- 🎯 Apply various discount rules such as:  
  - ⏳ Expiry date discount  
  - 🧀 Product type discount (cheese, wine)  
  - 🎉 Special date discount (e.g., March 23)  
  - 🔢 Quantity-based discounts  
  - 📦 Bulk purchase discounts  
  - 📱 Channel-based discounts (app purchases)  
  - 💳 Payment method discounts (Visa card)  
- 🧮 Calculate combined discount and final discounted price  
- 📝 Log processing events and errors to a log file  
- 💾 Persist discounted transaction results into PostgreSQL  

---

## 🚀 Getting Started

### 🔧 Prerequisites

- ☕ Java 8+ or compatible JVM installed  
- 🛠️ Scala 2.12+ installed  
- 🐘 PostgreSQL database setup  
- 🔌 PostgreSQL JDBC driver available  
- 📦 SBT (Scala Build Tool) or your preferred Scala build system  
- 📊 CSV input file with transaction data (format described below)  

### 🗄️ Database Setup

1. Create a PostgreSQL database (default used in code: `postgres`).  
2. Create the table `discounted_transactions` with columns matching the following schema:

| Column                 | Data Type         | Description                          |
|------------------------|-------------------|------------------------------------|
| transaction_timestamp  | TIMESTAMP         | Date and time of the transaction   |
| product_name           | VARCHAR(255)      | Name of the product                 |
| expiry_date            | DATE              | Product expiry date                 |
| quantity               | INTEGER           | Number of units purchased           |
| unit_price             | DOUBLE PRECISION  | Price per unit                     |
| channel                | VARCHAR(50)       | Sales channel (e.g., app, store)   |
| payment_method         | VARCHAR(50)       | Payment method used (e.g., Visa)   |
| applicable_discounts   | TEXT              | Applied discounts as a delimited string |
| final_discount         | DOUBLE PRECISION  | Final discount percentage applied  |
| final_price            | DOUBLE PRECISION  | Final price after discount          |
| processing_timestamp   | TIMESTAMP         | When the transaction was processed |

3. Update the database connection details (`DB_URL`, `DB_USER`, `DB_PASSWORD`) in the Scala source code as needed.

### 📁 CSV Input File Format

The CSV file should have the following columns (with header):
- `timestamp` format: ISO date-time (e.g., `2025-05-18T14:30:00`)  
- `expiryDate` format: ISO date (e.g., `2025-06-17`)  
- `quantity` as integer  
- `unitPrice` as decimal  
- `channel` and `paymentMethod` as strings  

---

## ▶️ Running the Application

1. Place your CSV input file in the location specified by `INPUT_CSV_PATH` in the source code.  
2. Compile and run the Scala program using your build tool, for example:  

   ```bash
   sbt run

3.	The program will:
	•	📥 Read and parse the CSV transactions
	•	🧮 Apply discount rules and compute final prices
	•	📝 Log processing info and errors to the log file
	•	💾 Write discounted transaction results to the PostgreSQL database
	4.	✅ Check the console output for the completion message.

⸻

📜 Logging
	•	🗂️ Log entries are appended to the file defined by LOG_FILE_PATH.
	•	⚠️ Includes info logs for each processed transaction and error logs for any failures.

⸻

🛠️ Extending and Customizing
	•	➕ Discount rules can be added or modified in the Discount Rules section of the code.
	•	🛢️ Database schema and connection parameters can be adapted in the Database Operations section.
	•	⚙️ Input/output paths are configurable via constants at the top of the code.

⸻

📄 License

This project is licensed under the ITI License — see the LICENSE file for details.
