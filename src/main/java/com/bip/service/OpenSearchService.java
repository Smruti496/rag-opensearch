package com.bip.service;

import com.bip.entity.Document;
import com.bip.util.GeminiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.poi.poifs.filesystem.OfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jsoup.Jsoup;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OpenSearchService {
    private final OpenSearchClient client;

    @Value("${spring.ai.openai.api-key}")
    private String openaiApiKey;

    private static final String INDEX_NAME = "documents";
    private static final String INDEX_NAME_FOR_EXCEL = "excel_documents";
    private final GeminiClient geminiClient;

    public OpenSearchService(OpenSearchClient client, GeminiClient geminiClient) {
        this.client = client;
        this.geminiClient = geminiClient;
    }

    // Index a document in OpenSearch
    public void addDocument(String id, String content) throws IOException {
        Document document = new Document(id, content);
        client.index(new IndexRequest.Builder<Document>()
                .index(INDEX_NAME)
                .id(id)
                .document(document)
                .build()
        );
        System.out.println("Indexed Document: " + content);
    }

    // Search for relevant documents
    public List<String> searchDocuments(String query) throws IOException {
        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(INDEX_NAME)
                .query(q -> q.match(m -> m.field("content").query(FieldValue.of(query))))
                .size(50)
                .build();

        SearchResponse<Document> response = client.search(searchRequest, Document.class);
        List<String> results = new ArrayList<>();

        for (Hit<Document> hit : response.hits().hits()) {
            results.add(hit.source().getContent());
        }
        return results;
    }

    public String generateAnswer(String query) throws IOException, ParseException {
        List<String> retrievedDocs = searchDocuments(query);

        if (retrievedDocs.isEmpty()) {
            return "No relevant information found.";
        }

        String context = String.join("\n", retrievedDocs);
        return callOpenAI(query, context);
    }

    // Call OpenAI API for LLM response
    private String callOpenAI(String query, String context) throws IOException, ParseException {
        String prompt = "Context:\n" + context + "\n\nUser Query: " + query + "\n\nAnswer:";

        // Construct request body using a Map
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4o-mini");

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are a helpful assistant."));
        messages.add(Map.of("role", "user", "content", prompt));

        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);

        // Convert to JSON
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(requestBody);

        // Make HTTP request
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost request = new HttpPost("https://api.openai.com/v1/chat/completions");
        request.setHeader("Authorization", "Bearer " + openaiApiKey);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new StringEntity(json));

        CloseableHttpResponse response = httpClient.execute(request);
        String responseBody = EntityUtils.toString(response.getEntity());
        httpClient.close();

        // Parse the JSON response to extract the "content" field
        JsonNode rootNode = objectMapper.readTree(responseBody);
        return rootNode.path("choices").get(0).path("message").path("content").asText();
    }

    public static String fetchTextContentFromUrl(String urlString) {
        try {
            // Fetch the HTML content from the URL
            org.jsoup.nodes.Document document = Jsoup.connect(urlString).get();

            // Extract the text content from the body of the HTML document
            return document.body().text();  // This will return the content inside <body> tag as plain text
        } catch (IOException e) {
            e.printStackTrace();
            return "Error fetching content: " + e.getMessage();
        }
    }

    public void addDocumentFromurl(String url) throws IOException {
        // Fetch the text content from the URL
        String content = fetchTextContentFromUrl(url);

        if (content.startsWith("Error fetching content:")) {
            System.out.println(content); // Log the error
            return; // Do not index if there's an error fetching the content
        }

        // Create and index the document (without specifying an ID)
        Document document = new Document(null, content); // Set id as null
        client.index(new IndexRequest.Builder<Document>()
                .index(INDEX_NAME)
                .document(document) // No ID, OpenSearch will generate one
                .build()
        );

        System.out.println("Indexed Document from URL: " + url);
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:
                return String.valueOf((long) cell.getNumericCellValue()); // Convert number to string safely

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                try {
                    return cell.getStringCellValue();  // Handle text formulas
                } catch (IllegalStateException e) {
                    return String.valueOf(cell.getNumericCellValue()); // Handle numeric formulas
                }

            case BLANK:
                return "";

            default:
                return "";
        }
    }

    public void uploadAndIndexExcelFile(MultipartFile file) throws IOException {
        String text = extractTextFromExcel(file);
        indexExcelContent(file.getOriginalFilename(), text);
    }

    private String extractTextFromExcel(MultipartFile file) throws IOException {
        StringBuilder extractedText = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {  // Auto-detects format
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        extractedText.append(cell.toString()).append(" ");
                    }
                    extractedText.append("\n");
                }
            }
        } catch (OfficeXmlFileException e) {
            throw new IOException("Unsupported Excel format. Please upload a valid .xls or .xlsx file.", e);
        }
        return extractedText.toString();
    }

    private void indexExcelContent(String fileName, String content) throws IOException {
        Map<String, Object> document = Map.of("content", content);
        IndexRequest<Map<String, Object>> indexRequest = new IndexRequest.Builder<Map<String, Object>>()
                .index(INDEX_NAME_FOR_EXCEL)
                .id(fileName)
                .document(document)
                .build();
        client.index(indexRequest);
    }

    public String generateExcelResponse(String query) throws IOException, ParseException {
        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(INDEX_NAME_FOR_EXCEL)
                .query(q -> q.match(m -> m.field("content").query(FieldValue.of(query))))
                .build();

        SearchResponse<Object> response = client.search(searchRequest, Object.class);
        List<String> retrievedDocs = response.hits().hits().stream()
                .map(Hit::source)
                .map(Object::toString)
                .collect(Collectors.toList());

        if (retrievedDocs.isEmpty()) {
            return "The requested information is not found in the documents.";
        }

        String context = String.join("\n", retrievedDocs);
        String templateString = """
                    You are an AI assistant that provides answers strictly based on the information available in the retrieved documents. 
                    Your response should be concise and informative. You can use bullet points if necessary.
                    If the requested information is not found in the provided documents, respond with: 
                    "The requested information is not found in the documents."

                    Retrieved Document(s):  
                    {documents}
                    User Query: {query}
                """;

        String finalPrompt = templateString.replace("{documents}", context).replace("{query}", query);

        // Use PromptTemplate for dynamic replacement
//        PromptTemplate promptTemplate = PromptTemplate.from(templateString);
//        Prompt finalPrompt = promptTemplate.apply(Map.of(
//                "documents", String.join("\n", retrievedDocs),
//                "query", query
//        ));

        return geminiClient.generateResponse(finalPrompt);
    }
}
