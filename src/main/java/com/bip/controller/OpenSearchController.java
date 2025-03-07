package com.bip.controller;

import com.bip.service.OpenSearchService;
import org.apache.hc.core5.http.ParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/open-search")
public class OpenSearchController {
    private final OpenSearchService openSearchService;

    public OpenSearchController(OpenSearchService openSearchService) {
        this.openSearchService = openSearchService;
    }

    @PostMapping("/add")
    public String addDocument(@RequestBody Map<String, String> request) throws IOException {
        openSearchService.addDocument(request.get("id"), request.get("content"));
        return "Document indexed successfully!";
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String query) throws IOException, ParseException {
        return openSearchService.generateAnswer(query);
    }

    @PostMapping("/from-url")
    public String addDocumentFromurl(@RequestBody Map<String, String> request) throws IOException {
        openSearchService.addDocumentFromurl(request.get("url"));
        return "Document indexed successfully from URL";
    }

    @PostMapping("/upload-excel")
    public String uploadExcelFile(@RequestParam("file") MultipartFile file) throws IOException {
        openSearchService.uploadAndIndexExcelFile(file);
        return "Excel file uploaded and indexed successfully";
    }

    @GetMapping("/excel-response")
    public String queryExcel(@RequestParam("query") String query) throws IOException, ParseException {
        return openSearchService.generateExcelResponse(query);
    }
}
