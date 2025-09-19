# 📝 PD Generator (Spring Boot + Ollama)

A Java Spring Boot application that uses a local AI model via [Ollama](https://ollama.com) to generate federal government position descriptions (PDs). Fully offline after setup.

---

## 📦 Requirements (See `requirements.txt` for full list)

- Java 17+
- Maven
- Ollama (for local AI model)
- 4–8 GB RAM minimum
- Internet (only for initial model download)

---

## 🚀 Features

- Generates short PDs for federal job series (e.g., Safety Officer, Program Analyst)
- Outputs: Summary, Key Responsibilities, and Requirements
- Sends requests to a locally running LLM via Ollama
- REST API for easy integration
- Fast and private — no external calls

---

## 🖥️ Setup Instructions (All Shells)

### 🔧 1. Clone the Repository

#### **Bash / Zsh / Linux / macOS Terminal**
```bash
git clone https://github.com/maki0311/pd-generator.git
cd pd-generator

#### powershell
git clone https://github.com/maki0311/pd-generator.git
cd .\pd-generator

#### Command Prompt (CMD)
git clone https://github.com/maki0311/pd-generator.git
cd pd-generator


