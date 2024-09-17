package org.example;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class Main {

    private static final String ASSISTANT_API = "https://prod.dvcbot.net/api/assts/v1";
    private static final String API_KEY = "your_api_key";
    private static final String ASSISTANT_ID = "your_assistant_id";

    public static void main(String[] args) {
        try {
            OkHttpClient client = new OkHttpClient();

            // Define messages
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("type", "text").put("text", "告訴我道路安全交通規則第54條"));

            // Create thread
            JSONObject thread = createThread(client);
            String threadId = thread.getString("id");
            // Send messages
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                sendMessage(client, threadId, message);
            }

            // Run assistant
            JSONObject run = runAssistant(client, threadId);
            String runId = run.getString("id");

            // Get Thread Run result
            JSONObject runResult = getThreadRunResults(client, threadId, runId);
            while (runResult.getString("status").equals("in_progress") && runResult.has("required_action")) {
                Thread.sleep(1000); // Wait before polling again
                runResult = getThreadRunResults(client, threadId, runId);
            }

            while (runResult.getString("status").equals("requires_action") && runResult.has("required_action")) {
                JSONArray toolCalls = runResult.getJSONObject("required_action").getJSONObject("submit_tool_outputs").getJSONArray("tool_calls");
                JSONArray outputs = new JSONArray();
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject call = toolCalls.getJSONObject(i);
                    JSONObject resp = postToolCall(client, threadId, call);
//                    .substring(0, 8000)
                    outputs.put(new JSONObject().put("tool_call_id", call.getString("id")).put("output", resp.toString()));
                }
                // submit_tool_outputs
                runResult = submitToolOutputsAndPoll(client, runResult.getString("id"), threadId, outputs);

                while (!runResult.getString("status").equals("completed") && run.has("required_action")) {
                    Thread.sleep(1000); // Wait before polling again
                    runResult = getThreadRunResults(client, threadId, runId);
                }
            }

            if (runResult.getString("status").equals("failed") && runResult.has("last_error")) {
                System.out.println(runResult.getJSONObject("last_error").toString(4));
            }

            JSONArray msgs = listMessages(client, threadId);
            deleteThread(client, threadId);
            System.out.println(msgs.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("text").getString("value"));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static JSONObject createThread(OkHttpClient client) throws IOException {
        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    private static void sendMessage(OkHttpClient client, String threadId, JSONObject message) throws IOException {
        JSONObject json = new JSONObject();
        json.put("role", "user");
        json.put("content", new JSONArray().put(message));

        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads/" + threadId + "/messages")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Handle response if needed
        }
    }

    private static JSONObject getThreadRunResults(OkHttpClient client, String threadId, String runId) throws IOException {
            // Build the request
            Request request = new Request.Builder()
                    .url(ASSISTANT_API + "/threads/" + threadId + "/runs/" + runId)
                    .addHeader("Authorization", "Bearer " + API_KEY)
                    .addHeader("OpenAI-Beta", "assistants=v2")
                    .build();

            // Execute the request
            try (Response response = client.newCall(request).execute()) {
                return new JSONObject(response.body().string());
            }
    }

    private static JSONObject runAssistant(OkHttpClient client, String threadId) throws IOException {
        JSONObject json = new JSONObject();
        json.put("assistant_id", ASSISTANT_ID);
        json.put("additional_instructions", "The current time is: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads/" + threadId + "/runs")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    private static JSONObject postToolCall(OkHttpClient client, String threadId, JSONObject call) throws IOException {
        JSONObject json = new JSONObject(call.getJSONObject("function").getString("arguments").toString());

        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(ASSISTANT_API + "/pluginapi")).newBuilder();
        urlBuilder.addQueryParameter("tid", threadId);
        urlBuilder.addQueryParameter("aid", ASSISTANT_ID);
        urlBuilder.addQueryParameter("pid", call.getJSONObject("function").getString("name"));

        String url = urlBuilder.build().toString();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    private static JSONObject submitToolOutputsAndPoll(OkHttpClient client, String runId, String threadId, JSONArray outputs) throws IOException {
        JSONObject json = new JSONObject();
        json.put("tool_outputs", outputs);

        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads/" + threadId + "/runs/" + runId + "/submit_tool_outputs")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    private static JSONArray listMessages(OkHttpClient client, String threadId) throws IOException {
        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads/" + threadId + "/messages?order=desc")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("OpenAI-Beta", "assistants=v2")
                .build();

        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string()).getJSONArray("data");
        }
    }

    private static void deleteThread(OkHttpClient client, String threadId) throws IOException {
        Request request = new Request.Builder()
                .url(ASSISTANT_API + "/threads/" + threadId)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .delete()
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Handle response if needed
        }
    }
}
