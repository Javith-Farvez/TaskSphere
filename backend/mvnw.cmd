@echo off
REM TaskSphere Maven wrapper helper (requires Maven installed)
where mvn >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
  echo Maven not found. Install Maven 3.9+ or open backend/ in IntelliJ IDEA and run TaskSphereApplication.
  exit /b 1
)
mvn %*
