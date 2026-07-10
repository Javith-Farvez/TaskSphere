-- TaskSphere Database Schema
-- MySQL 8+ | Database: tasksphere_db

CREATE DATABASE IF NOT EXISTS tasksphere_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE tasksphere_db;

-- Core tables are auto-created by Spring Boot JPA (ddl-auto=update).
-- Run this script to create the database before starting the backend.

-- Optional: verify connection
SELECT 'tasksphere_db ready' AS status;
