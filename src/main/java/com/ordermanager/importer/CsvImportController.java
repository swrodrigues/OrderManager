package com.ordermanager.importer;

import com.ordermanager.common.GlobalErrorHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

@Controller
public class CsvImportController {

    /** Browsers on Windows with Excel installed send CSV as application/vnd.ms-excel. */
    private static final Set<String> ACCEPTED_TYPES =
            Set.of("text/csv", "application/vnd.ms-excel", "text/plain", "application/octet-stream", "");

    private final CsvImportService importer;

    public CsvImportController(CsvImportService importer) {
        this.importer = importer;
    }

    @GetMapping("/import")
    public String page(Model model) {
        model.addAttribute("header", String.join(",", CsvImportService.HEADER));
        return "import/index";
    }

    @PostMapping("/import")
    public String upload(@RequestParam("file") MultipartFile file, Authentication auth,
                         HttpServletRequest req, HttpServletResponse res, Model model) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv") || !ACCEPTED_TYPES.contains(type)) {
            return problem(req, res, model, HttpStatus.UNPROCESSABLE_ENTITY, "Please upload a .csv file");
        }
        try (InputStream in = file.getInputStream()) {
            model.addAttribute("result", importer.importCsv(name, in, auth.getName()));
        } catch (ImportRejectedException e) {
            return problem(req, res, model, HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (DuplicateImportException e) {
            return problem(req, res, model, HttpStatus.CONFLICT, e.getMessage());
        }
        return GlobalErrorHandler.isHtmx(req) ? "import/result :: result" : page(model);
    }

    private String problem(HttpServletRequest req, HttpServletResponse res, Model model, HttpStatus status, String msg) {
        res.setStatus(status.value());
        model.addAttribute("kind", "error");
        model.addAttribute("message", msg);
        return GlobalErrorHandler.isHtmx(req) ? "fragments/alert :: flash" : page(model);
    }
}
