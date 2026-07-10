package com.tasksphere.service;

import com.tasksphere.entity.Booking;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Generates a real, downloadable PDF invoice for a completed/paid booking —
 * same PDFBox pattern already used by ReportService for P&L exports.
 */
@Service
public class InvoiceService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    public byte[] generateInvoicePdf(Booking b) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font bold = PDType1Font.HELVETICA_BOLD;
            PDType1Font regular = PDType1Font.HELVETICA;

            double platformFee = round2(b.getAmount() * 0.08);
            double subtotal = round2(b.getAmount() - platformFee);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 790;

                text(cs, bold, 20, 50, y, "TaskSphere"); y -= 16;
                text(cs, regular, 9, 50, y, "On-Demand Service Marketplace"); y -= 30;

                text(cs, bold, 15, 50, y, "INVOICE"); y -= 4;
                line(cs, 50, y, 545, y); y -= 20;

                text(cs, bold, 10, 50, y, "Invoice No:");
                text(cs, regular, 10, 160, y, "TS-INV-" + b.getId());
                text(cs, bold, 10, 340, y, "Date:");
                text(cs, regular, 10, 420, y, b.getCreatedAt() != null ? b.getCreatedAt().format(DT) : "-");
                y -= 16;

                text(cs, bold, 10, 50, y, "Payment Ref:");
                text(cs, regular, 10, 160, y, b.getPaymentRef() != null ? b.getPaymentRef() : "-");
                text(cs, bold, 10, 340, y, "Status:");
                text(cs, regular, 10, 420, y, b.getPaymentStatus() != null ? b.getPaymentStatus().name() : "-");
                y -= 30;

                text(cs, bold, 11, 50, y, "Billed To");
                text(cs, bold, 11, 320, y, "Service Provider"); y -= 15;
                text(cs, regular, 10, 50, y, b.getCustomer() != null ? b.getCustomer().getName() : "Customer");
                text(cs, regular, 10, 320, y, b.getProvider() != null ? b.getProvider().getName() : "Not yet assigned"); y -= 13;
                if (b.getCustomer() != null && b.getCustomer().getEmail() != null) {
                    text(cs, regular, 9, 50, y, b.getCustomer().getEmail()); y -= 13;
                }
                y -= 12;

                text(cs, bold, 11, 50, y, "Service Details"); y -= 16;
                line(cs, 50, y, 545, y); y -= 16;

                text(cs, bold, 10, 50, y, "Description");
                text(cs, bold, 10, 340, y, "Address");
                text(cs, bold, 10, 480, y, "Amount");
                y -= 14;
                line(cs, 50, y, 545, y); y -= 16;

                text(cs, regular, 10, 50, y, b.getService() != null ? b.getService() : "Service");
                text(cs, regular, 9, 340, y, truncate(b.getAddress(), 30));
                text(cs, regular, 10, 480, y, "Rs. " + fmt(subtotal));
                y -= 24;

                line(cs, 320, y, 545, y); y -= 16;
                text(cs, regular, 10, 340, y, "Subtotal");
                text(cs, regular, 10, 480, y, "Rs. " + fmt(subtotal));
                y -= 16;
                text(cs, regular, 10, 340, y, "Platform Fee (8%)");
                text(cs, regular, 10, 480, y, "Rs. " + fmt(platformFee));
                y -= 16;
                line(cs, 320, y, 545, y); y -= 18;
                text(cs, bold, 12, 340, y, "Total Paid");
                text(cs, bold, 12, 480, y, "Rs. " + fmt(b.getAmount()));
                y -= 40;

                text(cs, regular, 8, 50, 40, "This is a system-generated invoice from TaskSphere. For support, contact support@tasksphere.in");
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s == null ? "" : s);
        cs.endText();
    }

    private void line(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.setLineWidth(0.5f);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private double round2(double d) { return Math.round(d * 100.0) / 100.0; }

    private String fmt(double d) { return String.format(Locale.US, "%,.2f", d); }
}
