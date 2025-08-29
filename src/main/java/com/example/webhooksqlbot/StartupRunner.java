package com.example.webhooksqlbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;

@Component
public class StartupRunner implements CommandLineRunner {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {

        // 1) Generate webhook
        String genUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA";

        Map<String, String> payload = Map.of(
                "name",  "John Doe",          // <-- your name
                "regNo", "22BCE1825",         // <-- your roll number
                "email", "john@example.com"   // <-- your email
        );

        HttpHeaders genHeaders = new HttpHeaders();
        genHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> genReq = new HttpEntity<>(payload, genHeaders);

        ResponseEntity<String> genResp = restTemplate.postForEntity(genUrl, genReq, String.class);
        if (!genResp.getStatusCode().is2xxSuccessful() || genResp.getBody() == null) {
            throw new IllegalStateException("generateWebhook failed: " + genResp);
        }

        System.out.println("Webhook response: " + genResp.getBody());

        // Parse webhook URL and access token
        JsonNode json = mapper.readTree(genResp.getBody());
        String webhookUrl  = json.get("webhook").asText();
        String accessToken = json.get("accessToken").asText();

        // 2) Choose SQL query based on last two digits of roll number
        String regNo = "22BCE1825";
        int lastTwoDigits = Integer.parseInt(regNo.substring(regNo.length() - 2));
        String finalQuery;

        if (lastTwoDigits % 2 == 0) {
            // Even → Question 2 (not your case)
            finalQuery = "SELECT e1.EMP_ID, e1.FIRST_NAME, e1.LAST_NAME, d.DEPARTMENT_NAME, " +
                         "COUNT(e2.EMP_ID) AS YOUNGER_EMPLOYEES_COUNT " +
                         "FROM EMPLOYEE e1 " +
                         "JOIN DEPARTMENT d ON e1.DEPARTMENT = d.DEPARTMENT_ID " +
                         "LEFT JOIN EMPLOYEE e2 ON e1.DEPARTMENT = e2.DEPARTMENT AND e2.DOB > e1.DOB " +
                         "GROUP BY e1.EMP_ID, e1.FIRST_NAME, e1.LAST_NAME, d.DEPARTMENT_NAME " +
                         "ORDER BY e1.EMP_ID DESC;";
        } else {
            // Odd → Question 1 (your case)
            finalQuery = "SELECT p.AMOUNT AS SALARY, CONCAT(e.FIRST_NAME, ' ', e.LAST_NAME) AS NAME, " +
                         "FLOOR(DATEDIFF(CURDATE(), e.DOB)/365) AS AGE, d.DEPARTMENT_NAME " +
                         "FROM PAYMENTS p " +
                         "JOIN EMPLOYEE e ON p.EMP_ID = e.EMP_ID " +
                         "JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID " +
                         "WHERE DAY(p.PAYMENT_TIME) <> 1 " +
                         "AND p.AMOUNT = (SELECT MAX(AMOUNT) FROM PAYMENTS WHERE DAY(PAYMENT_TIME) <> 1);";
        }

        // 3) Build submission body
        String submitBody = mapper.writeValueAsString(Map.of("finalQuery", finalQuery));

        // 4) Send to webhook with JWT Authorization
        HttpHeaders subHeaders = new HttpHeaders();
        subHeaders.setContentType(MediaType.APPLICATION_JSON);
        subHeaders.setBearerAuth(accessToken); // "Authorization: Bearer <token>"

        HttpEntity<String> subReq = new HttpEntity<>(submitBody, subHeaders);

        ResponseEntity<String> subResp;
        try {
            subResp = restTemplate.postForEntity(webhookUrl, subReq, String.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            // Retry with raw token if Bearer fails
            HttpHeaders alt = new HttpHeaders();
            alt.setContentType(MediaType.APPLICATION_JSON);
            alt.set("Authorization", accessToken);
            subResp = restTemplate.postForEntity(webhookUrl, new HttpEntity<>(submitBody, alt), String.class);
        }

        System.out.println("Submission response: " + subResp.getStatusCode() + " -> " + subResp.getBody());
    }
}
