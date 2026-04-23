package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedDocument;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.txt.CharsetDetector;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Excel/CSV parser using Apache POI for .xlsx and OpenCSV for .csv.
 * - .xlsx: row-by-row access via POI XSSFWorkbook
 * - .csv: auto-detected encoding via Tika CharsetDetector, then OpenCSV reader
 * - Each sheet becomes a ParsedDocument section with sheet name as title
 * - Rows converted to Markdown table format
 */
@Service
@Slf4j
public class ExcelParserService {

    public List<ParsedDocument> parse(InputStream inputStream, String format) {
        try {
            if ("csv".equalsIgnoreCase(format)) {
                return parseCsv(inputStream);
            } else if ("xlsx".equalsIgnoreCase(format) || "xls".equalsIgnoreCase(format)) {
                return parseExcel(inputStream);
            } else {
                throw new BizException("Excel/CSV 文件格式错误或包含不可读字符");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Excel/CSV 文件解析失败", e);
            throw new BizException("Excel/CSV 文件格式错误或包含不可读字符");
        }
    }

    private List<ParsedDocument> parseExcel(InputStream inputStream) throws Exception {
        List<ParsedDocument> sections = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                String markdownTable = sheetToMarkdownTable(sheet);
                sections.add(new ParsedDocument(
                        sheetName,
                        markdownTable,
                        Collections.emptyList(),
                        "xlsx"
                ));
            }
        }
        return sections;
    }

    private List<ParsedDocument> parseCsv(InputStream inputStream) throws Exception {
        // Detect encoding using Tika CharsetDetector
        byte[] bytes = inputStream.readAllBytes();
        String encoding = CharsetDetector.detect(bytes).getName();
        if (encoding == null) {
            encoding = "UTF-8";
        }

        List<ParsedDocument> sections = new ArrayList<>();
        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes), encoding)) {
            CSVReader csvReader = new CSVReaderBuilder(reader).build();
            List<String[]> rows = csvReader.readAll();

            if (rows.isEmpty()) {
                return Collections.emptyList();
            }

            StringBuilder table = new StringBuilder();

            // First row as header
            String[] header = rows.get(0);
            table.append("|");
            for (String cell : header) {
                table.append(" ").append(cell.trim()).append(" |");
            }
            table.append("\n|");
            for (int i = 0; i < header.length; i++) {
                table.append("---|");
            }
            table.append("\n");

            // Remaining rows as data
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                table.append("|");
                for (String cell : row) {
                    table.append(" ").append(cell.trim()).append(" |");
                }
                table.append("\n");
            }

            sections.add(new ParsedDocument(
                    "表格数据",
                    table.toString().trim(),
                    Collections.emptyList(),
                    "csv"
            ));
        }

        return sections;
    }

    private String sheetToMarkdownTable(Sheet sheet) {
        StringBuilder table = new StringBuilder();
        boolean hasHeader = false;

        for (Row row : sheet) {
            // Skip empty rows
            if (isRowEmpty(row)) continue;

            if (!hasHeader) {
                // First row as header
                table.append("|");
                for (Cell cell : row) {
                    table.append(" ").append(getCellValue(cell)).append(" |");
                }
                table.append("\n|");
                int cellCount = row.getLastCellNum() - row.getFirstCellNum();
                for (int i = 0; i < cellCount; i++) {
                    table.append("---|");
                }
                table.append("\n");
                hasHeader = true;
            } else {
                // Data row
                table.append("|");
                for (Cell cell : row) {
                    table.append(" ").append(getCellValue(cell)).append(" |");
                }
                table.append("\n");
            }
        }

        return table.toString().trim();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                // Avoid scientific notation
                double numVal = cell.getNumericCellValue();
                if (numVal == (long) numVal) {
                    return String.valueOf((long) numVal);
                }
                return String.valueOf(numVal);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK
                    && !getCellValue(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
