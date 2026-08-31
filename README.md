# Budget-and-Finance-Tracker
This is a student budget and finance tracker that would allow the user to input and view there finances over the span of 
time to helpe people budget and better plan with their money.


# Budget and Finance Tracker

A team software engineering project for tracking personal finances, budgets, and transactions.

## Tech Stack

- IntelliJ IDEA
- Git and GitHub
- React
- TypeScript
- Vite
- Java 21
- Spring Boot
- Maven
- Supabase PostgreSQL

## Project Structure

```text
Budget-and-Finance-Tracker/
├── backend/      # Java Spring Boot backend
├── frontend/     # React + TypeScript frontend
├── docs/         # Project documentation
├── supabase/     # Supabase/database files
└── README.md
```

More folders can be added by team members as they are needed.

## Required Software

Install the following before working on the project:

- IntelliJ IDEA
- Git
- Node.js
- Java JDK 21

You can check your installations with:

```bash
git --version
node --version
npm --version
java -version
javac -version
```

## Getting the Project

Clone the GitHub repository through IntelliJ.

1. Open IntelliJ IDEA.
2. Select **Get from VCS** or **Git > Clone**.
3. Sign in with your own GitHub account.
4. Select the Budget-and-Finance-Tracker repository.
5. Clone and open the project.

Each team member should use their own GitHub account so commits are credited to the correct person.

## Supabase Setup

Each team member should use their own Supabase account and have access to the shared Supabase project.

The backend requires an environment variable called:

```text
SUPABASE_DB_URL
```

In IntelliJ:

1. Go to **Run > Edit Configurations**.
2. Select the Spring Boot backend configuration.
3. Make sure Java 21 and the backend module are selected.
4. Add an environment variable named `SUPABASE_DB_URL`.
5. Use the Supabase JDBC Session Pooler connection string as the value.
6. Apply and save the configuration.

Do not commit database passwords, API keys, or other secrets to GitHub.

## Running the Backend

The Spring Boot backend is located in:

```text
backend/
```

It can be started using the green Run button in IntelliJ.

It can also be started from the terminal on Windows:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The backend normally runs at:

```text
http://localhost:8080
```

## Running the Frontend

From the project root:

```bash
cd frontend
npm install
npm run dev
```

The frontend normally runs at:

```text
http://localhost:5173
```

`npm install` normally only needs to be run after first cloning the project or when dependencies change.

## GitHub Workflow

Do not normally work directly on `main`.

Before starting a new task:

```bash
git checkout main
git pull
git checkout -b feature/your-feature-name
```

Example:

```bash
git checkout -b feature/transaction-page
```

After completing work:

```bash
git add .
git commit -m "Add transaction page"
git push -u origin feature/transaction-page
```

Then:

1. Open a Pull Request on GitHub.
2. Have another team member review the Pull Request.
3. Make any needed changes.
4. Merge the Pull Request into `main`.

## Team Rules

- Use your own GitHub account.
- Use your own Supabase account.
- Do not share account passwords.
- Do not commit passwords, API keys, or secrets.
- Use branches for development.
- Use Pull Requests to merge work into `main`.
- Have another team member review Pull Requests.
- Pull the newest version of `main` before starting new work.
- Team members may create additional folders and organize their work as the project grows.

## Current Base Setup

The current project provides the basic foundation for:

```text
React + TypeScript Frontend
            ↓
Java Spring Boot Backend
            ↓
Supabase PostgreSQL Database
```

The team will build the project's features and additional structure from this foundation.