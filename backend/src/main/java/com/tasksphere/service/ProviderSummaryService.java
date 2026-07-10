package com.tasksphere.service;

import com.tasksphere.dto.ProviderDtos.ProviderSummary;
import com.tasksphere.entity.Service;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.FavoriteProviderRepository;
import com.tasksphere.repository.ReviewRepository;
import com.tasksphere.repository.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.List;

/**
 * Builds {@link ProviderSummary} DTOs (rating, review count, completed jobs,
 * cheapest service price, favorited flag) for any User with role PROVIDER.
 * Shared by the public providers list and the customer favorites endpoints
 * so both surfaces stay consistent.
 */
@org.springframework.stereotype.Service
public class ProviderSummaryService {

    @Autowired private ReviewRepository           reviewRepo;
    @Autowired private BookingRepository          bookingRepo;
    @Autowired private ServiceRepository           serviceRepo;
    @Autowired private FavoriteProviderRepository  favoriteRepo;

    public ProviderSummary build(User provider, User viewingCustomer) {
        ProviderSummary s = ProviderSummary.basic(provider);
        s.setAvgRating(round1(reviewRepo.avgRatingByProvider(provider)));
        s.setReviewCount(reviewRepo.countByProvider(provider));
        s.setCompletedJobs(bookingRepo.countCompletedByProvider(provider));

        List<Service> services = serviceRepo.findByProviderAndEnabledOrderByCreatedAtDesc(provider, true);
        services.stream().map(Service::getPrice).filter(p -> p != null)
                .min(Comparator.naturalOrder())
                .ifPresent(s::setFromPrice);
        services.stream().findFirst().ifPresent(svc -> s.setRole(svc.getCategory()));

        if (viewingCustomer != null) {
            s.setFavorited(favoriteRepo.existsByCustomerAndProvider(viewingCustomer, provider));
        }
        return s;
    }

    private Double round1(Double d) {
        if (d == null) return 0.0;
        return Math.round(d * 10.0) / 10.0;
    }
}
