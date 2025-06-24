# Meta-Ping

This is a serverless application that implements a simple string reversal function using Spring Cloud Function.

## How to Use Function

Send a POST request to `/reverseString` with a text payload to reverse a string:

```bash
curl -X POST http://localhost:8080/reverseString -H "Content-Type: text/plain" -d "Hello, World!"
```

Response:
```
!dlroW ,olleH
```
