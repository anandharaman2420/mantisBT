package com.mantis.service;

import com.mantis.domain.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** Generates CSV and Excel (SpreadsheetML) exports of issues. */
@Service
public class ExportService {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public byte[] exportCsv(List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM so Excel opens UTF-8 text correctly
        sb.append('\uFEFF');

        // Headers
        sb.append(String.join(",", List.of(
                "\"ID\"", "\"Project\"", "\"Category\"", "\"Severity\"", "\"Priority\"", "\"Status\"",
                "\"Resolution\"", "\"Reporter\"", "\"Assigned To\"", "\"Summary\"", "\"Description\"",
                "\"Steps to Reproduce\"", "\"Additional Information\"", "\"Platform\"", "\"OS\"", "\"OS Build\"",
                "\"Product Version\"", "\"Product Build\"", "\"Target Version\"", "\"Fixed in Version\"",
                "\"View State\"", "\"Date Submitted\"", "\"Last Updated\"", "\"Tags\""
        ))).append("\r\n");

        for (Issue i : issues) {
            String tags = i.getTags().stream().map(Tag::getName).sorted().collect(Collectors.joining(", "));
            String handler = i.getHandler() != null ? i.getHandler().getUsername() : "";
            String category = i.getCategory() != null ? i.getCategory().getName() : "";
            String dateSub = i.getDateSubmitted() != null ? DATE_FMT.format(i.getDateSubmitted()) : "";
            String dateUpd = i.getLastUpdated() != null ? DATE_FMT.format(i.getLastUpdated()) : "";

            sb.append(String.join(",", List.of(
                    String.valueOf(i.getId()),
                    csvCell(i.getProject().getName()),
                    csvCell(category),
                    csvCell(Lookups.label(Lookups.SEVERITY, i.getSeverity())),
                    csvCell(Lookups.label(Lookups.PRIORITY, i.getPriority())),
                    csvCell(Lookups.label(Lookups.STATUS, i.getStatus())),
                    csvCell(Lookups.label(Lookups.RESOLUTION, i.getResolution())),
                    csvCell(i.getReporter().getUsername()),
                    csvCell(handler),
                    csvCell(i.getSummary()),
                    csvCell(i.getDescription()),
                    csvCell(i.getStepsToReproduce()),
                    csvCell(i.getAdditionalInformation()),
                    csvCell(i.getPlatform()),
                    csvCell(i.getOS()),
                    csvCell(i.getOsBuild()),
                    csvCell(i.getVersion()),
                    csvCell(i.getBuild()),
                    csvCell(i.getTargetVersion()),
                    csvCell(i.getFixedInVersion()),
                    csvCell(Lookups.label(Lookups.VIEW_STATE, i.getViewState())),
                    csvCell(dateSub),
                    csvCell(dateUpd),
                    csvCell(tags)
            ))).append("\r\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportExcelXml(List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sb.append("<?mso-application progid=\"Excel.Sheet\"?>\r\n");
        sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"\r\n");
        sb.append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"\r\n");
        sb.append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"\r\n");
        sb.append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"\r\n");
        sb.append(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">\r\n");

        // Styles
        sb.append(" <Styles>\r\n");
        sb.append("  <Style ss:ID=\"Default\" ss:Name=\"Normal\">\r\n");
        sb.append("   <Alignment ss:Vertical=\"Center\"/>\r\n");
        sb.append("   <Font ss:FontName=\"Segoe UI\" ss:Size=\"10\" ss:Color=\"#000000\"/>\r\n");
        sb.append("  </Style>\r\n");
        sb.append("  <Style ss:ID=\"Header\">\r\n");
        sb.append("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\" ss:WrapText=\"1\"/>\r\n");
        sb.append("   <Borders>\r\n");
        sb.append("    <Border ss:Position=\"Bottom\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"#A0AEC0\"/>\r\n");
        sb.append("   </Borders>\r\n");
        sb.append("   <Font ss:FontName=\"Segoe UI\" ss:Size=\"10\" ss:Color=\"#FFFFFF\" ss:Bold=\"1\"/>\r\n");
        sb.append("   <Interior ss:Color=\"#2B6CB0\" ss:Pattern=\"Solid\"/>\r\n");
        sb.append("  </Style>\r\n");
        sb.append("  <Style ss:ID=\"RowEven\">\r\n");
        sb.append("   <Interior ss:Color=\"#F7FAFC\" ss:Pattern=\"Solid\"/>\r\n");
        sb.append("  </Style>\r\n");
        sb.append(" </Styles>\r\n");

        sb.append(" <Worksheet ss:Name=\"Issues\">\r\n");
        sb.append("  <Table ss:DefaultRowHeight=\"18\">\r\n");
        sb.append("   <Column ss:Width=\"55\"/>\r\n");  // ID
        sb.append("   <Column ss:Width=\"110\"/>\r\n"); // Project
        sb.append("   <Column ss:Width=\"90\"/>\r\n");  // Category
        sb.append("   <Column ss:Width=\"75\"/>\r\n");  // Severity
        sb.append("   <Column ss:Width=\"75\"/>\r\n");  // Priority
        sb.append("   <Column ss:Width=\"85\"/>\r\n");  // Status
        sb.append("   <Column ss:Width=\"85\"/>\r\n");  // Resolution
        sb.append("   <Column ss:Width=\"90\"/>\r\n");  // Reporter
        sb.append("   <Column ss:Width=\"90\"/>\r\n");  // Assigned To
        sb.append("   <Column ss:Width=\"220\"/>\r\n"); // Summary
        sb.append("   <Column ss:Width=\"280\"/>\r\n"); // Description
        sb.append("   <Column ss:Width=\"120\"/>\r\n"); // Date Submitted
        sb.append("   <Column ss:Width=\"120\"/>\r\n"); // Last Updated
        sb.append("   <Column ss:Width=\"120\"/>\r\n"); // Tags

        // Header Row
        sb.append("   <Row ss:StyleID=\"Header\" ss:Height=\"24\">\r\n");
        String[] headers = {"ID", "Project", "Category", "Severity", "Priority", "Status", "Resolution",
                "Reporter", "Assigned To", "Summary", "Description", "Submitted", "Updated", "Tags"};
        for (String h : headers) {
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(h)).append("</Data></Cell>\r\n");
        }
        sb.append("   </Row>\r\n");

        // Data Rows
        boolean even = false;
        for (Issue i : issues) {
            String style = even ? " ss:StyleID=\"RowEven\"" : "";
            even = !even;

            String tags = i.getTags().stream().map(Tag::getName).sorted().collect(Collectors.joining(", "));
            String handler = i.getHandler() != null ? i.getHandler().getUsername() : "";
            String category = i.getCategory() != null ? i.getCategory().getName() : "";
            String dateSub = i.getDateSubmitted() != null ? DATE_FMT.format(i.getDateSubmitted()) : "";
            String dateUpd = i.getLastUpdated() != null ? DATE_FMT.format(i.getLastUpdated()) : "";

            sb.append("   <Row").append(style).append(">\r\n");
            sb.append("    <Cell><Data ss:Type=\"Number\">").append(i.getId()).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(i.getProject().getName())).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(category)).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(Lookups.label(Lookups.SEVERITY, i.getSeverity()))).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(Lookups.label(Lookups.PRIORITY, i.getPriority()))).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(Lookups.label(Lookups.STATUS, i.getStatus()))).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(Lookups.label(Lookups.RESOLUTION, i.getResolution()))).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(i.getReporter().getUsername())).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(handler)).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(i.getSummary())).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(i.getDescription())).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(dateSub)).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(dateUpd)).append("</Data></Cell>\r\n");
            sb.append("    <Cell><Data ss:Type=\"String\">").append(xmlEscape(tags)).append("</Data></Cell>\r\n");
            sb.append("   </Row>\r\n");
        }

        sb.append("  </Table>\r\n");
        sb.append(" </Worksheet>\r\n");
        sb.append("</Workbook>\r\n");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvCell(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String xmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
