# 📝 Federal Position Description Generator

A Spring Boot web application that generates federal position descriptions using OpenAI and USAJobs API integration.

---

## 📦 Requirements

- **Java 17+**
- **Maven 3.6+**
- **OpenAI API Key** ([Get one here](https://openai.com/api/))
- **USAJobs API Credentials** ([Register here](https://developer.usajobs.gov/APIRequest/Index))
- **8+ GB RAM** recommended

---

## 🚀 Quick Start

### 1. Clone Repository

```bash
git clone https://github.com/maki0311/positiondescriptiongenerator.git
cd positiondescriptiongenerator
```

### 2. Configure API Keys

Edit `src/main/resources/application.properties`:

```properties
# Server
server.port=8080

# OpenAI
OPENAI_API_KEY = your openai api key

# USAJobs API

USAJOBS_USER_AGENT = your usajobs email
USAJOBS_API_KEY = your usajobs api key
```

### 3. Run Application

```bash
mvn clean install
mvn spring-boot:run
```

### 4. Open Browser

```
http://localhost:8080
```

---

## 🎯 Features

- **5-Step Wizard** - Guided PD creation process
- **AI-Powered Analysis** - Automatic job series and grade recommendations
- **USAJobs Integration** - Real-time federal job data
- **FES/GSSG Classification** - Dual evaluation systems for GS grades
- **PDF Generation** - Professional federal PD format
- **Duty Rewriter** - AI-enhanced duty statements

---

## 🔌 Main API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/generate-pd` | POST | Generate complete PD |
| `/api/recommend-series` | POST | Get job series from duties |
| `/api/rewrite-duties` | POST | AI rewrite duty statements |
| `/api/proxy/usajobs` | GET | Fetch USAJobs data |

---

## 📁 Project Structure

```
Allen_Management_Group/
├── src/main/
│   ├── java/com/example/pdgenerator/
│   │   ├── PdGeneratorApplication.java
│   │   ├── controller/PdGeneratorController.java
│   │   ├── service/PdService.java
│   │   └── service/PdfProcessingService.java
│   ├── resources/
│   │   ├── application.properties
│   │   └── pdfs/
│   └── public/
│       ├── index.html
│       ├── PD_Script.js
│       └── styles.css
└── pom.xml
```

---

## 🛠️ Tech Stack

- **Backend**: Spring Boot 3.x, Java 17
- **Frontend**: Vanilla JavaScript, HTML5, CSS3
- **AI**: OpenAI gpt-4
- **APIs**: USAJobs, REST
- **PDF**: Apache PDFBox

---

## 🐛 Troubleshooting

**App won't start?**
- Check Java version: `java -version` (need 17+)
- Verify API keys in `application.properties`
- Ensure port 8080 is available

**AI requests fail?**
- Verify OpenAI API key is valid
- Check internet connection
- Review console logs

**No USAJobs data?**
- Confirm USAJobs API key and user agent
- Check rate limits (1000/hour)

---

## 📄 License

Provided as-is for federal government use.

---

**Version**: 1.0.0
**Last Updated**: October 2025


