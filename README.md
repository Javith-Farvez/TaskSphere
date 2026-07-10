# TaskSphere

On-demand service marketplace — Spring Boot backend + premium HTML frontend with role-based dashboards.

## Live Demo

- **Frontend:** https://task-sphere-three.vercel.app
- **Backend API:** https://tasksphere-wxxd.onrender.com *(optional)*


## Project Structure

```
TaskSphere/
├── backend/          Spring Boot API (port 8080)
├── frontend/         HTML/CSS/JS dashboards
├── database/         MySQL setup scripts
└── uploads/          File uploads (runtime)
```

## Quick Start

### 1. Database (MySQL)

```bash
mysql -u root -p < database/tasksphere.sql
```

Update credentials in `backend/src/main/resources/application.properties` if needed (default: `root` / `root`).

### 2. Backend

```bash
cd backend
mvn spring-boot:run
```

API: `http://localhost:8080/api`  
Frontend (served by Spring Boot): `http://localhost:8080/`

### 3. Frontend (optional — Live Server)

Open `frontend/index.html` with Live Server (port 5500) — API calls go to `localhost:8080`.

## Login & Role Routing

Open **`index.html`** (or `http://localhost:8080/`). After login, you are routed automatically:

| Role     | Dashboard                |
|----------|--------------------------|
| Customer | `customer-app.html`      |
| Provider | `provider-dashboard.html`|
| Admin    | `admin-dashboard.html`   |

### Demo Accounts (password: `demo1234`)

| Email              | Role     |
|--------------------|----------|
| customer@demo.com  | Customer |
| provider@demo.com  | Provider |
| admin@demo.com     | Admin    |

## Features

- JWT authentication with role-based access
- Customer booking, tracking, payments, reviews
- Provider job management, earnings, availability
- Admin analytics, users, complaints, categories
- Demo mode when backend is offline

## Tech Stack

- **Backend:** Java 21, Spring Boot 3.2, Spring Security, JWT, MySQL, JPA
- **Frontend:** HTML5, CSS3, JavaScript, Leaflet.js
- **Payments:** Razorpay (optional, feature-flagged)
- **Maps:** Google Maps API (optional) / OpenStreetMap fallback

## Configuration

Edit `backend/src/main/resources/application.properties` for:

- MySQL connection
- JWT secret
- Razorpay keys (`app.payments.enabled=true`)
- Google Maps (`app.maps.enabled=true`)
- Email SMTP (`app.mail.enabled=true`)

## License

Academic / portfolio project.

## Fixing "Access denied for user 'root'@'localhost'"

This error means MySQL is running, but the password in `application.properties`
(`root`/`root` by default) doesn't match your actual MySQL root password.
It is **not a code bug** — it's a local credential mismatch. Two ways to fix it:

**Option A — reset MySQL's root password to match the config:**
```sql
mysql -u root -p
ALTER USER 'root'@'localhost' IDENTIFIED BY 'root';
FLUSH PRIVILEGES;
```

**Option B (recommended) — create a dedicated app user and point the app at it:**
```sql
mysql -u root -p
CREATE USER 'tasksphere'@'localhost' IDENTIFIED BY 'YourStrongPassword123!';
CREATE DATABASE IF NOT EXISTS tasksphere_db;
GRANT ALL PRIVILEGES ON tasksphere_db.* TO 'tasksphere'@'localhost';
FLUSH PRIVILEGES;
```
Then run the app with env vars instead of editing the properties file:
```powershell
$env:DB_USERNAME="tasksphere"; $env:DB_PASSWORD="YourStrongPassword123!"; mvn spring-boot:run
```
`application.properties` now reads `DB_USERNAME`/`DB_PASSWORD`/`DB_URL` from the
environment first and only falls back to `root`/`root` if they're not set, so
you no longer need to hardcode real credentials in the file.
