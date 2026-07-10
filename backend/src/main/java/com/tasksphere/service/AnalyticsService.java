package com.tasksphere.service;

import com.tasksphere.entity.Booking;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.ReviewRepository;
import com.tasksphere.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Powers every chart/summary-stat on the admin dashboard's Overview and
 * Revenue tabs — Monthly Revenue, Booking Growth, Provider Performance,
 * Customer Growth, Category Analytics, Top Providers, Top Services, and
 * Revenue Trend — all computed live from MySQL (Booking/User/Review),
 * nothing hardcoded. Bar-chart shapes ({bars:[...], labels:[...]}) match
 * exactly what the existing admin-dashboard.html CHART_DATA object already
 * expects, so the frontend only needs to swap its data source.
 */
@org.springframework.stereotype.Service
public class AnalyticsService {

    @Autowired private BookingRepository bookingRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ReviewRepository reviewRepo;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");
    private static final String[] DOW = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};

    public Map<String, Object> dashboard() {
        List<Booking> all = bookingRepo.findAll();
        List<Booking> completed = all.stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.COMPLETED)
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingsTrend", bookingsTrend(completed));
        out.put("revenueTrend", revenueTrend(completed));
        out.put("categoryBreakdown", categoryBreakdown(completed));
        out.put("topProviders", topProviders(completed));
        out.put("topServices", topServices(completed));
        out.put("customerGrowth", customerGrowth());
        out.put("providerPerformance", providerPerformance());

        double gmv = completed.stream().mapToDouble(Booking::getAmount).sum();
        double platformFee = gmv * 0.08;
        out.put("gmv", round2(gmv));
        out.put("platformFee", round2(platformFee));
        out.put("providerPayouts", round2(gmv - platformFee));
        out.put("takeRatePercent", 8.0);
        out.put("totalBookings", all.size());
        out.put("completedBookings", completed.size());

        // ── Overview hero KPI row — GMV (30 days) / Active Users /
        // Active Providers / Bookings Today. These are intentionally
        // separate from the all-time gmv/completedBookings above, which
        // power the Revenue tab instead. ──
        LocalDate cutoff30 = LocalDate.now().minusDays(30);
        double gmv30 = completed.stream()
                .filter(b -> b.getCreatedAt() != null && !b.getCreatedAt().toLocalDate().isBefore(cutoff30))
                .mapToDouble(Booking::getAmount).sum();
        out.put("gmv30Days", round2(gmv30));

        long activeUsers = userRepo.findByRole(com.tasksphere.entity.User.Role.CUSTOMER).stream()
                .filter(u -> u.getStatus() == com.tasksphere.entity.User.Status.ACTIVE).count();
        out.put("activeUsers", activeUsers);

        long activeProviders = userRepo.findByRole(com.tasksphere.entity.User.Role.PROVIDER).stream()
                .filter(u -> u.getStatus() == com.tasksphere.entity.User.Status.ACTIVE).count();
        out.put("activeProviders", activeProviders);

        LocalDate today = LocalDate.now();
        long bookingsToday = all.stream()
                .filter(b -> b.getCreatedAt() != null && b.getCreatedAt().toLocalDate().isEqual(today)).count();
        out.put("bookingsToday", bookingsToday);

        out.put("generatedAt", LocalDate.now().toString());
        return out;
    }

    // ── Bookings trend (week / month / year — revenue per bucket) ──────
    private Map<String, Object> bookingsTrend(List<Booking> completed) {
        LocalDate today = LocalDate.now();

        // Week: last 7 days
        double[] weekBars = new double[7];
        LocalDate weekStart = today.minusDays(6);
        for (Booking b : completed) {
            if (b.getCreatedAt() == null) continue;
            LocalDate d = b.getCreatedAt().toLocalDate();
            long idx = java.time.temporal.ChronoUnit.DAYS.between(weekStart, d);
            if (idx >= 0 && idx < 7) weekBars[(int) idx] += b.getAmount();
        }
        List<String> weekLabels = new ArrayList<>();
        for (int i = 0; i < 7; i++) weekLabels.add(DOW[weekStart.plusDays(i).getDayOfWeek().getValue() - 1]);

        // Month: last 30 days
        double[] monthBars = new double[30];
        LocalDate monthStart = today.minusDays(29);
        for (Booking b : completed) {
            if (b.getCreatedAt() == null) continue;
            LocalDate d = b.getCreatedAt().toLocalDate();
            long idx = java.time.temporal.ChronoUnit.DAYS.between(monthStart, d);
            if (idx >= 0 && idx < 30) monthBars[(int) idx] += b.getAmount();
        }
        List<String> monthLabels = new ArrayList<>();
        for (int i = 0; i < 30; i += 5) monthLabels.add(monthStart.plusDays(i).format(DAY_FMT));
        monthLabels.add(today.format(DAY_FMT));

        // Year: last 12 months
        Map<YearMonth, Double> yearMap = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.now().minusMonths(11);
        for (int i = 0; i < 12; i++) { yearMap.put(cursor, 0.0); cursor = cursor.plusMonths(1); }
        for (Booking b : completed) {
            if (b.getCreatedAt() == null) continue;
            YearMonth ym = YearMonth.from(b.getCreatedAt());
            if (yearMap.containsKey(ym)) yearMap.merge(ym, b.getAmount(), Double::sum);
        }

        double todayRevenue = completed.stream()
                .filter(b -> b.getCreatedAt() != null && b.getCreatedAt().toLocalDate().equals(today))
                .mapToDouble(Booking::getAmount).sum();
        double weekRevenue = Arrays.stream(weekBars).sum();
        double avgBookingValue = completed.isEmpty() ? 0 : completed.stream().mapToDouble(Booking::getAmount).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("week", Map.of("bars", boxed(weekBars), "labels", weekLabels));
        result.put("month", Map.of("bars", boxed(monthBars), "labels", monthLabels));
        result.put("year", Map.of("bars", new ArrayList<>(yearMap.values()), "labels",
                yearMap.keySet().stream().map(ym -> ym.format(MONTH_FMT)).toList()));
        result.put("todayRevenue", round2(todayRevenue));
        result.put("weekRevenue", round2(weekRevenue));
        result.put("avgBookingValue", round2(avgBookingValue));
        return result;
    }

    // ── Revenue trend (6M / Year) + MoM growth + YTD ────────────────────
    private Map<String, Object> revenueTrend(List<Booking> completed) {
        Map<YearMonth, Double> sixMonthMap = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.now().minusMonths(5);
        for (int i = 0; i < 6; i++) { sixMonthMap.put(cursor, 0.0); cursor = cursor.plusMonths(1); }

        Map<YearMonth, Double> yearMap = new LinkedHashMap<>();
        cursor = YearMonth.now().minusMonths(11);
        for (int i = 0; i < 12; i++) { yearMap.put(cursor, 0.0); cursor = cursor.plusMonths(1); }

        for (Booking b : completed) {
            if (b.getCreatedAt() == null) continue;
            YearMonth ym = YearMonth.from(b.getCreatedAt());
            if (sixMonthMap.containsKey(ym)) sixMonthMap.merge(ym, b.getAmount(), Double::sum);
            if (yearMap.containsKey(ym)) yearMap.merge(ym, b.getAmount(), Double::sum);
        }

        double thisMonth = sixMonthMap.getOrDefault(YearMonth.now(), 0.0);
        double lastMonth = sixMonthMap.getOrDefault(YearMonth.now().minusMonths(1), 0.0);
        double momGrowth = lastMonth > 0 ? ((thisMonth - lastMonth) / lastMonth) * 100 : (thisMonth > 0 ? 100.0 : 0.0);
        double ytd = yearMap.entrySet().stream()
                .filter(e -> e.getKey().getYear() == YearMonth.now().getYear())
                .mapToDouble(Map.Entry::getValue).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sixMonth", Map.of("bars", new ArrayList<>(sixMonthMap.values()), "labels",
                sixMonthMap.keySet().stream().map(ym -> ym.format(MONTH_FMT)).toList()));
        result.put("year", Map.of("bars", new ArrayList<>(yearMap.values()), "labels",
                yearMap.keySet().stream().map(ym -> ym.format(MONTH_FMT)).toList()));
        result.put("thisMonthRevenue", round2(thisMonth));
        result.put("momGrowthPercent", round2(momGrowth));
        result.put("ytdRevenue", round2(ytd));
        return result;
    }

    // ── Category analytics (top 4 + Others, by % of revenue) ───────────
    private List<Map<String, Object>> categoryBreakdown(List<Booking> completed) {
        Map<String, Double> byCategory = completed.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getService() == null ? "Other" : b.getService(),
                        Collectors.summingDouble(Booking::getAmount)));
        double total = byCategory.values().stream().mapToDouble(Double::doubleValue).sum();

        List<Map.Entry<String, Double>> sorted = byCategory.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();

        List<Map<String, Object>> result = new ArrayList<>();
        double othersAmount = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var e = sorted.get(i);
            if (i < 4) {
                result.add(Map.of(
                        "category", e.getKey(),
                        "amount", round2(e.getValue()),
                        "percent", total > 0 ? round2((e.getValue() / total) * 100) : 0.0
                ));
            } else {
                othersAmount += e.getValue();
            }
        }
        if (othersAmount > 0) {
            result.add(Map.of(
                    "category", "Others",
                    "amount", round2(othersAmount),
                    "percent", total > 0 ? round2((othersAmount / total) * 100) : 0.0
            ));
        }
        return result;
    }

    // ── Top providers by real completed revenue ─────────────────────────
    private List<Map<String, Object>> topProviders(List<Booking> completed) {
        Map<User, List<Booking>> byProvider = completed.stream()
                .filter(b -> b.getProvider() != null)
                .collect(Collectors.groupingBy(Booking::getProvider));

        return byProvider.entrySet().stream()
                .map(e -> {
                    double revenue = e.getValue().stream().mapToDouble(Booking::getAmount).sum();
                    return Map.<String, Object>of(
                            "name", e.getKey().getName(),
                            "revenue", round2(revenue),
                            "jobs", e.getValue().size(),
                            "rating", round1(safe(reviewRepo.avgRatingByProvider(e.getKey())))
                    );
                })
                .sorted((a, b) -> Double.compare((double) b.get("revenue"), (double) a.get("revenue")))
                .limit(5)
                .toList();
    }

    // ── Top services by booking count ────────────────────────────────
    private List<Map<String, Object>> topServices(List<Booking> completed) {
        Map<String, List<Booking>> byService = completed.stream()
                .collect(Collectors.groupingBy(b -> b.getService() == null ? "Other" : b.getService()));

        return byService.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "service", e.getKey(),
                        "bookings", e.getValue().size(),
                        "revenue", round2(e.getValue().stream().mapToDouble(Booking::getAmount).sum())
                ))
                .sorted((a, b) -> Integer.compare((int) b.get("bookings"), (int) a.get("bookings")))
                .limit(5)
                .toList();
    }

    // ── Customer growth — new CUSTOMER signups per month, last 12 months ─
    private Map<String, Object> customerGrowth() {
        List<User> customers = userRepo.findByRole(User.Role.CUSTOMER);
        Map<YearMonth, Long> monthMap = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.now().minusMonths(11);
        for (int i = 0; i < 12; i++) { monthMap.put(cursor, 0L); cursor = cursor.plusMonths(1); }
        for (User u : customers) {
            if (u.getCreatedAt() == null) continue;
            YearMonth ym = YearMonth.from(u.getCreatedAt());
            if (monthMap.containsKey(ym)) monthMap.merge(ym, 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bars", new ArrayList<>(monthMap.values()));
        result.put("labels", monthMap.keySet().stream().map(ym -> ym.format(MONTH_FMT)).toList());
        result.put("totalCustomers", customers.size());
        return result;
    }

    // ── Provider performance — avg rating + completion rate across platform ─
    private Map<String, Object> providerPerformance() {
        List<User> providers = userRepo.findByRole(User.Role.PROVIDER);
        double avgRating = round1(safe(reviewRepo.avgRatingOverall()));

        long totalAssigned = bookingRepo.findAll().stream()
                .filter(b -> b.getProvider() != null
                        && b.getStatus() != Booking.BookingStatus.PENDING)
                .count();
        long totalCompleted = bookingRepo.findAll().stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.COMPLETED)
                .count();
        double completionRate = totalAssigned > 0 ? round1((totalCompleted * 100.0) / totalAssigned) : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalProviders", providers.size());
        result.put("activeProviders", providers.stream().filter(p -> p.getStatus() == User.Status.ACTIVE).count());
        result.put("avgRating", avgRating);
        result.put("completionRatePercent", completionRate);
        return result;
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private List<Double> boxed(double[] arr) {
        List<Double> list = new ArrayList<>();
        for (double d : arr) list.add(round2(d));
        return list;
    }

    private double safe(Double d) { return d == null ? 0.0 : d; }
    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }
    private double round2(double d) { return Math.round(d * 100.0) / 100.0; }
}
