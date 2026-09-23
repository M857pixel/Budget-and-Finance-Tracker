export MISTRAL_API_KEY="insert-api-key-here"

curl https://api.mistral.ai/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MISTRAL_API_KEY" \
  -d '{
    "model": "ministral-8b-latest",
    "messages": [
      {
        "role": "user",
        "content": "You are going to select a command for a database output of the following. You must simply select the command to use and nothing else. Select from the following for the given prompt: Select * FROM Food Select * FROM Birthday Select * FROM Tax. The prompt is How much have I spent on food"
      }
    ]
  }'
``