package com.tasksphere.config;

import com.tasksphere.entity.Booking;
import com.tasksphere.entity.Complaint;
import com.tasksphere.entity.ServiceCategory;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.CategoryRepository;
import com.tasksphere.repository.ComplaintRepository;
import com.tasksphere.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final CategoryRepository categoryRepo;
    private final ComplaintRepository complaintRepo;
    private final BookingRepository bookingRepo;

    @Override
    public void run(String... args) {
        seedUser("Mohammed Javith", "customer@demo.com", "demo1234", "+91 98765 43210", User.Role.CUSTOMER);
        seedUser("Aisha Nair",      "provider@demo.com", "demo1234", "+91 98420 11234", User.Role.PROVIDER);
        seedUser("Admin User",      "admin@demo.com",    "demo1234", "+91 98765 00001", User.Role.ADMIN);
        seedUser("Rajan Kumar",     "rajan@demo.com",    "demo1234", "+91 98765 11111", User.Role.PROVIDER);
        seedUser("Sara M.",         "sara@demo.com",     "demo1234", "+91 91234 56789", User.Role.CUSTOMER);
        log.info("✅ TaskSphere demo data seeded — 5 users ready");

        seedDefaultWorkingHours("provider@demo.com");
        seedDefaultWorkingHours("rajan@demo.com");

        seedCategories();
        seedComplaints();
    }

    private void seedDefaultWorkingHours(String email) {
        userRepo.findByEmail(email).ifPresent(p -> {
            if (p.getWorkingDays() == null) {
                p.setWorkingDays("MON,TUE,WED,THU,FRI");
                p.setWorkStartTime("09:00");
                p.setWorkEndTime("18:00");
                p.setMaxJobsPerDay(3);
                p.setIsOnline(true);
                userRepo.save(p);
            }
        });
    }

    private void seedUser(String name, String email, String password, String phone, User.Role role) {
        if (!userRepo.existsByEmail(email)) {
            userRepo.save(User.builder()
                    .name(name).email(email)
                    .password(encoder.encode(password))
                    .phone(phone).role(role)
                    .build());
            log.info("  Seeded {} ({})", name, role);
        }
    }

    private void seedCategories() {
        if (categoryRepo.count() > 0) return;
        Object[][] defaults = {
                {"Cleaning", "🧹", "Home & office cleaning services", 1},
                {"Plumbing", "🔧", "Pipe repair, installation & maintenance", 2},
                {"Electrical", "⚡", "Wiring, repairs & installations", 3},
                {"Locksmith", "🔐", "Lock repair & emergency lockout services", 4},
                {"Repairs", "🛠️", "General appliance & home repairs", 5},
                {"Painting", "🎨", "Interior & exterior painting", 6},
                {"Gardening", "🌿", "Landscaping & lawn maintenance", 7},
                {"Pest Control", "🐜", "Pest inspection & extermination", 8},
        };
        for (Object[] d : defaults) {
            categoryRepo.save(ServiceCategory.builder()
                    .name((String) d[0]).icon((String) d[1]).description((String) d[2])
                    .enabled(true).sortOrder((Integer) d[3]).build());
        }
        log.info("  Seeded {} service categories", defaults.length);
    }

    private void seedComplaints() {
        if (complaintRepo.count() > 0) return;
        Optional<User> customer = userRepo.findByEmail("customer@demo.com");
        Optional<User> provider = userRepo.findByEmail("provider@demo.com");
        if (customer.isEmpty() || provider.isEmpty()) return;

        List<Booking> bookings = bookingRepo.findByCustomerOrderByCreatedAtDesc(customer.get());
        Booking sampleBooking = bookings.isEmpty() ? null : bookings.get(0);

        complaintRepo.save(Complaint.builder()
                .customer(customer.get()).provider(provider.get()).booking(sampleBooking)
                .subject("Provider arrived late")
                .description("The provider arrived almost 40 minutes after the scheduled slot without prior notice.")
                .priority(Complaint.ComplaintPriority.MEDIUM)
                .status(Complaint.ComplaintStatus.OPEN)
                .build());

        complaintRepo.save(Complaint.builder()
                .customer(customer.get()).provider(provider.get()).booking(sampleBooking)
                .subject("Overcharged for service")
                .description("I was charged ₹150 more than the quoted price shown in the app.")
                .priority(Complaint.ComplaintPriority.HIGH)
                .status(Complaint.ComplaintStatus.IN_PROGRESS)
                .adminResponse("We are reviewing the payment logs with the finance team.")
                .build());

        log.info("  Seeded 2 sample complaints");
    }
}
