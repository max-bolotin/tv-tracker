package com.tvtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvtracker.exception.GlobalExceptionHandler;
import com.tvtracker.exception.InvalidImportException;
import com.tvtracker.scheduler.DailyUpdateScheduler;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.DataImportService;
import com.tvtracker.storage.ImportService;
import com.tvtracker.storage.JsonStorageService;
import com.tvtracker.storage.MovieStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataControllerImportTest {
    private static final String USER_ID = "import-user";

    private DataImportService importer;
    private MockMvc mockMvc;
    private MockedStatic<CurrentUserContext> userContext;

    @BeforeEach
    void setUp() {
        importer = mock(DataImportService.class);
        DataController controller = new DataController(
                mock(JsonStorageService.class),
                mock(ImportService.class),
                importer,
                mock(MovieStorageService.class),
                new ObjectMapper(),
                mock(DailyUpdateScheduler.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userContext = mockStatic(CurrentUserContext.class);
        userContext.when(CurrentUserContext::currentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        userContext.close();
    }

    @Test
    void multipartImportReturnsCombinedSummaryShape() throws Exception {
        when(importer.importData(any(), eq(USER_ID)))
                .thenReturn(new DataImportService.ImportSummary(2, 1, java.util.List.of("Missing")));

        mockMvc.perform(multipart("/api/data/import")
                        .file(new MockMultipartFile("file", "backup.json", "application/json",
                                "{\"version\":2}".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showsImported").value(2))
                .andExpect(jsonPath("$.moviesImported").value(1))
                .andExpect(jsonPath("$.failedTitles[0]").value("Missing"));
    }

    @Test
    void multipartImportReturnsBadRequestForInvalidPayloads() throws Exception {
        when(importer.importData(any(), eq(USER_ID))).thenAnswer(invocation -> {
            var input = (java.io.InputStream) invocation.getArgument(0);
            String content = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (content.equals("[]") || content.contains("\"shows\":{}")) {
                throw new InvalidImportException("Invalid import payload");
            }
            return new DataImportService.ImportSummary(0, 0, java.util.List.of());
        });

        for (String invalid : java.util.List.of("[]", "{\"shows\":{}}")) {
            mockMvc.perform(multipart("/api/data/import")
                            .file(new MockMultipartFile("file", "bad.json", "application/json",
                                    invalid.getBytes())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid import payload"));
        }
    }
}
