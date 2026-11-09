package com.pocketledger.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

import java.io.IOException;
// handle File upload logic
// later will verify the file is CSV and will be parsed to be turned into Transactions
// Currently receives file as a MulitpartFile, copies its bytes and holds them in the server session
@RestController
@RequestMapping("/api")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173"},
        allowCredentials = "true"
)
public class FileUploadController {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleUploadError(
            ResponseStatusException exception) {
        String message = exception.getReason() == null
                ? "The CSV upload was rejected."
                : exception.getReason();

        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", message));
    }

    @PostMapping(
            value = "/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadFile(
            @RequestParam("file") MultipartFile file,
            HttpSession session
    ) throws IOException {
        byte[] normalizedContents = CSVUploadNormalizer.normalize(file);

        UploadedFile uploadedFile = new UploadedFile(
                file.getOriginalFilename(),
                normalizedContents
        );

        session.setAttribute("uploadedFile", uploadedFile);
    }

    public record UploadedFile(String filename, byte[] contents) {}
}