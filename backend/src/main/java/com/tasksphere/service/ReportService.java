package com.tasksphere.service;

import com.tasksphere.entity.Booking;
import com.tasksphere.repository.BookingRepository;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    @Autowired private BookingRepository bookingRepo;
    @Autowired private com.tasksphere.repository.UserRepository userRepo;
    @Autowired private com.tasksphere.repository.ReviewRepository reviewRepo;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    // ── Revenue breakdown (used by /reports/revenue and exports) ───────────
    public Map<String, Object> buildRevenueReport() {
        List<Booking> all = bookingRepo.findAll();
        List<Booking> completed = all.stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.COMPLETED)
                .collect(Collectors.toList());

        double gmv = completed.stream().mapToDouble(Booking::getAmount).sum();
        double platformFee = gmv * 0.08;
        double providerPayout = gmv - platformFee;

        // Group by service category (Booking.service holds the service name/category)
        Map<String, Double> byCategory = completed.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getService() == null ? "Other" : b.getService(),
                        Collectors.summingDouble(Booking::getAmount)));

        // Group by month (last 6 months)
        Map<String, Double> byMonth = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.now().minusMonths(5);
        for (int i = 0; i < 6; i++) {
            byMonth.put(cursor.format(DateTimeFormatter.ofPattern("MMM yyyy")), 0.0);
            cursor = cursor.plusMonths(1);
        }
        for (Booking b : completed) {
            if (b.getCreatedAt() == null) continue;
            String key = YearMonth.from(b.getCreatedAt()).format(DateTimeFormatter.ofPattern("MMM yyyy"));
            if (byMonth.containsKey(key)) byMonth.merge(key, b.getAmount(), Double::sum);
        }

        // Top providers by revenue
        Map<String, Double> byProvider = completed.stream()
                .filter(b -> b.getProvider() != null)
                .collect(Collectors.groupingBy(b -> b.getProvider().getName(),
                        Collectors.summingDouble(Booking::getAmount)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gmv", gmv);
        result.put("platformFee", platformFee);
        result.put("providerPayout", providerPayout);
        result.put("completedBookings", completed.size());
        result.put("totalBookings", all.size());
        result.put("byCategory", byCategory);
        result.put("byMonth", byMonth);
        result.put("byProvider", byProvider);
        result.put("generatedAt", LocalDate.now().toString());
        return result;
    }

    public byte[] exportCustomersExcel() throws IOException {
        List<com.tasksphere.entity.User> customers = userRepo.findByRole(com.tasksphere.entity.User.Role.CUSTOMER);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Customers");
            CellStyle headStyle = headerStyle(wb);

            String[] headers = {"ID", "Name", "Email", "Phone", "Status", "Total Bookings", "Total Spent (₹)", "Joined"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(headStyle);
            }

            List<Booking> allBookings = bookingRepo.findAll();
            int rowIdx = 1;
            for (com.tasksphere.entity.User u : customers) {
                List<Booking> mine = allBookings.stream()
                        .filter(b -> b.getCustomer() != null && b.getCustomer().getId().equals(u.getId()))
                        .toList();
                double spent = mine.stream()
                        .filter(b -> b.getStatus() == Booking.BookingStatus.COMPLETED)
                        .mapToDouble(b -> b.getAmount() != null ? b.getAmount() : 0).sum();
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(u.getId());
                row.createCell(1).setCellValue(u.getName() != null ? u.getName() : "");
                row.createCell(2).setCellValue(u.getEmail() != null ? u.getEmail() : "");
                row.createCell(3).setCellValue(u.getPhone() != null ? u.getPhone() : "");
                row.createCell(4).setCellValue(u.getStatus().name());
                row.createCell(5).setCellValue(mine.size());
                row.createCell(6).setCellValue(spent);
                row.createCell(7).setCellValue(u.getCreatedAt() != null ? u.getCreatedAt().format(DT) : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportProvidersExcel() throws IOException {
        List<com.tasksphere.entity.User> providers = userRepo.findByRole(com.tasksphere.entity.User.Role.PROVIDER);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Providers");
            CellStyle headStyle = headerStyle(wb);

            String[] headers = {"ID", "Name", "Email", "Phone", "Status", "Online", "Jobs Completed", "Avg Rating", "Total Earned (₹)", "Joined"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(headStyle);
            }

            List<Booking> allBookings = bookingRepo.findAll();
            int rowIdx = 1;
            for (com.tasksphere.entity.User u : providers) {
                List<Booking> mine = allBookings.stream()
                        .filter(b -> b.getProvider() != null && b.getProvider().getId().equals(u.getId())
                                && b.getStatus() == Booking.BookingStatus.COMPLETED)
                        .toList();
                double earned = mine.stream().mapToDouble(b -> b.getAmount() != null ? b.getAmount() * 0.92 : 0).sum(); // after 8% platform fee
                Double avgRating = reviewRepo.avgRatingByProvider(u);
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(u.getId());
                row.createCell(1).setCellValue(u.getName() != null ? u.getName() : "");
                row.createCell(2).setCellValue(u.getEmail() != null ? u.getEmail() : "");
                row.createCell(3).setCellValue(u.getPhone() != null ? u.getPhone() : "");
                row.createCell(4).setCellValue(u.getStatus().name());
                row.createCell(5).setCellValue(Boolean.TRUE.equals(u.getIsOnline()) ? "Online" : "Offline");
                row.createCell(6).setCellValue(mine.size());
                row.createCell(7).setCellValue(avgRating != null ? avgRating : 0);
                row.createCell(8).setCellValue(earned);
                row.createCell(9).setCellValue(u.getCreatedAt() != null ? u.getCreatedAt().format(DT) : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportReviewsExcel() throws IOException {
        List<com.tasksphere.entity.Review> reviews = reviewRepo.findAll();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Reviews");
            CellStyle headStyle = headerStyle(wb);

            String[] headers = {"ID", "Customer", "Provider", "Rating", "Comment", "Provider Reply", "Date"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(headStyle);
            }

            int rowIdx = 1;
            for (com.tasksphere.entity.Review r : reviews) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.getId());
                row.createCell(1).setCellValue(r.getCustomer() != null ? r.getCustomer().getName() : "");
                row.createCell(2).setCellValue(r.getProvider() != null ? r.getProvider().getName() : "");
                row.createCell(3).setCellValue(r.getRating() != null ? r.getRating() : 0);
                row.createCell(4).setCellValue(r.getComment() != null ? r.getComment() : "");
                row.createCell(5).setCellValue(r.getReply() != null ? r.getReply() : "");
                row.createCell(6).setCellValue(r.getCreatedAt() != null ? r.getCreatedAt().format(DT) : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private CellStyle headerStyle(Workbook wb) {
        CellStyle headStyle = wb.createCellStyle();
        Font headFont = wb.createFont();
        headFont.setBold(true);
        headFont.setColor(IndexedColors.WHITE.getIndex());
        headStyle.setFont(headFont);
        headStyle.setFillForegroundColor(IndexedColors.TEAL.getIndex());
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return headStyle;
    }

    // ── Excel export ────────────────────────────────────────────────────
    public byte[] exportBookingsExcel() throws IOException {
        List<Booking> bookings = bookingRepo.findAll();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Bookings");

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.TEAL.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"Booking ID", "Customer", "Provider", "Service", "Amount (₹)",
                    "Status", "Payment Status", "Created At"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }

            int rowIdx = 1;
            for (Booking b : bookings) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue("BK-" + b.getId());
                row.createCell(1).setCellValue(b.getCustomer() != null ? b.getCustomer().getName() : "");
                row.createCell(2).setCellValue(b.getProvider() != null ? b.getProvider().getName() : "Unassigned");
                row.createCell(3).setCellValue(b.getService() != null ? b.getService() : "");
                row.createCell(4).setCellValue(b.getAmount() != null ? b.getAmount() : 0);
                row.createCell(5).setCellValue(b.getStatus().name());
                row.createCell(6).setCellValue(b.getPaymentStatus().name());
                row.createCell(7).setCellValue(b.getCreatedAt() != null ? b.getCreatedAt().format(DT) : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportRevenueExcel() throws IOException {
        Map<String, Object> report = buildRevenueReport();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.TEAL.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Sheet 1 — Summary
            Sheet summary = wb.createSheet("Summary");
            String[][] rows = {
                    {"Metric", "Value"},
                    {"Gross Bookings Value (GMV)", "₹" + fmt(report.get("gmv"))},
                    {"Platform Fee (8%)", "₹" + fmt(report.get("platformFee"))},
                    {"Provider Payouts", "₹" + fmt(report.get("providerPayout"))},
                    {"Completed Bookings", String.valueOf(report.get("completedBookings"))},
                    {"Total Bookings", String.valueOf(report.get("totalBookings"))},
                    {"Generated On", String.valueOf(report.get("generatedAt"))}
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = summary.createRow(r);
                for (int cI = 0; cI < 2; cI++) {
                    Cell c = row.createCell(cI);
                    c.setCellValue(rows[r][cI]);
                    if (r == 0) c.setCellStyle(headStyle);
                }
            }
            summary.autoSizeColumn(0);
            summary.autoSizeColumn(1);

            // Sheet 2 — By category
            Sheet byCat = wb.createSheet("Revenue by Category");
            Row catHead = byCat.createRow(0);
            catHead.createCell(0).setCellValue("Category");
            catHead.createCell(1).setCellValue("Revenue (₹)");
            catHead.getCell(0).setCellStyle(headStyle);
            catHead.getCell(1).setCellStyle(headStyle);
            int r = 1;
            @SuppressWarnings("unchecked")
            Map<String, Double> byCategory = (Map<String, Double>) report.get("byCategory");
            for (Map.Entry<String, Double> e : byCategory.entrySet()) {
                Row row = byCat.createRow(r++);
                row.createCell(0).setCellValue(e.getKey());
                row.createCell(1).setCellValue(e.getValue());
            }
            byCat.autoSizeColumn(0);
            byCat.autoSizeColumn(1);

            // Sheet 3 — By month
            Sheet byMonth = wb.createSheet("Monthly Trend");
            Row mHead = byMonth.createRow(0);
            mHead.createCell(0).setCellValue("Month");
            mHead.createCell(1).setCellValue("Revenue (₹)");
            mHead.getCell(0).setCellStyle(headStyle);
            mHead.getCell(1).setCellStyle(headStyle);
            int mr = 1;
            @SuppressWarnings("unchecked")
            Map<String, Double> byMonthMap = (Map<String, Double>) report.get("byMonth");
            for (Map.Entry<String, Double> e : byMonthMap.entrySet()) {
                Row row = byMonth.createRow(mr++);
                row.createCell(0).setCellValue(e.getKey());
                row.createCell(1).setCellValue(e.getValue());
            }
            byMonth.autoSizeColumn(0);
            byMonth.autoSizeColumn(1);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── PDF export (Revenue / P&L summary) ──────────────────────────────
    public byte[] exportRevenuePdf() throws IOException {
        Map<String, Object> report = buildRevenueReport();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font bold = PDType1Font.HELVETICA_BOLD;
            PDType1Font regular = PDType1Font.HELVETICA;

            float y = 780;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(bold, 18);
                cs.newLineAtOffset(50, y);
                cs.showText("TaskSphere — Revenue & P&L Report");
                cs.endText();
                y -= 22;

                cs.beginText();
                cs.setFont(regular, 10);
                cs.newLineAtOffset(50, y);
                cs.showText("Generated on: " + report.get("generatedAt"));
                cs.endText();
                y -= 30;

                String[][] lines = {
                        {"Gross Bookings Value (GMV)", "Rs. " + fmt(report.get("gmv"))},
                        {"Platform Fee (8%)", "Rs. " + fmt(report.get("platformFee"))},
                        {"Provider Payouts", "Rs. " + fmt(report.get("providerPayout"))},
                        {"Completed Bookings", String.valueOf(report.get("completedBookings"))},
                        {"Total Bookings (all statuses)", String.valueOf(report.get("totalBookings"))}
                };

                cs.setFont(bold, 13);
                cs.beginText();
                cs.newLineAtOffset(50, y);
                cs.showText("Financial Summary");
                cs.endText();
                y -= 18;

                for (String[] line : lines) {
                    cs.beginText();
                    cs.setFont(regular, 11);
                    cs.newLineAtOffset(50, y);
                    cs.showText(line[0] + ":");
                    cs.endText();
                    cs.beginText();
                    cs.setFont(bold, 11);
                    cs.newLineAtOffset(320, y);
                    cs.showText(line[1]);
                    cs.endText();
                    y -= 18;
                }

                y -= 15;
                cs.beginText();
                cs.setFont(bold, 13);
                cs.newLineAtOffset(50, y);
                cs.showText("Revenue by Category");
                cs.endText();
                y -= 18;

                @SuppressWarnings("unchecked")
                Map<String, Double> byCategory = (Map<String, Double>) report.get("byCategory");
                for (Map.Entry<String, Double> e : byCategory.entrySet()) {
                    if (y < 60) break;
                    cs.beginText();
                    cs.setFont(regular, 11);
                    cs.newLineAtOffset(50, y);
                    cs.showText(e.getKey() + ":");
                    cs.endText();
                    cs.beginText();
                    cs.setFont(regular, 11);
                    cs.newLineAtOffset(320, y);
                    cs.showText("Rs. " + fmt(e.getValue()));
                    cs.endText();
                    y -= 16;
                }
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Excel export — Audit Logs ───────────────────────────────────────
    public byte[] exportAuditLogsExcel(java.util.List<com.tasksphere.entity.AuditLog> logs) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Audit Logs");

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.TEAL.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"Timestamp", "Actor", "Action", "Entity Type", "Entity ID", "Details"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }

            int rowIdx = 1;
            for (com.tasksphere.entity.AuditLog a : logs) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(a.getCreatedAt() != null ? a.getCreatedAt().format(DT) : "");
                row.createCell(1).setCellValue(a.getActorEmail() != null ? a.getActorEmail() : "");
                row.createCell(2).setCellValue(a.getAction().name());
                row.createCell(3).setCellValue(a.getEntityType() != null ? a.getEntityType() : "");
                row.createCell(4).setCellValue(a.getEntityId() != null ? a.getEntityId() : "");
                row.createCell(5).setCellValue(a.getDetails() != null ? a.getDetails() : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private String fmt(Object val) {
        double d = val instanceof Double ? (Double) val : Double.parseDouble(String.valueOf(val));
        return String.format(Locale.US, "%,.2f", d);
    }
}
