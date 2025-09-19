// Backend rate limit handler (Node.js/Express)
async function makeOpenAIRequestWithRetry(requestFunction, maxRetries = 3) {
    for (let attempt = 0; attempt < maxRetries; attempt++) {
        try {
            return await requestFunction();
        } catch (error) {
            if (error.status === 429 || error.code === 'rate_limit_exceeded') {
                const delay = Math.pow(2, attempt) * 1000; // Exponential backoff: 1s, 2s, 4s
                console.log(`Rate limit hit. Retrying in ${delay}ms... (attempt ${attempt + 1}/${maxRetries})`);
                
                if (attempt < maxRetries - 1) {
                    await new Promise(resolve => setTimeout(resolve, delay));
                    continue;
                }
            }
            throw error;
        }
    }
}

// Example usage in your API endpoint
app.post('/api/generate', async (req, res) => {
    try {
        const result = await makeOpenAIRequestWithRetry(async () => {
            return await openai.chat.completions.create({
                model: "gpt-3.5-turbo", // or "gpt-4" if you have access
                messages: [
                    { role: "system", content: "You are a helpful assistant." },
                    { role: "user", content: req.body.prompt || "Hello!" }
                ], // your prompt/messages here
                stream: true,
                max_tokens: 2000,
                temperature: 0.7
            });
        });
        
        // Handle streaming response...
        
    } catch (error) {
        if (error.status === 429) {
            res.status(429).json({ 
                error: 'API rate limit exceeded. Please wait a moment and try again.',
                retryAfter: 60 // seconds
            });
        } else {
            res.status(500).json({ error: 'Generation failed: ' + error.message });
        }
    }
});

// Rate limiting middleware for your Express server
const rateLimit = require('express-rate-limit');

const apiLimiter = rateLimit({
    windowMs: 60 * 1000, // 1 minute
    max: 10, // limit each IP to 10 requests per windowMs
    message: 'Too many requests, please try again later.',
    standardHeaders: true,
    legacyHeaders: false,
});

// Apply to API routes
app.use('/api/', apiLimiter);