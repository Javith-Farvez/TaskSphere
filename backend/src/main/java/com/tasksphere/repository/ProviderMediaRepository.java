package com.tasksphere.repository;

import com.tasksphere.entity.ProviderMedia;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderMediaRepository extends JpaRepository<ProviderMedia, Long> {
    List<ProviderMedia> findByProviderOrderByUploadedAtDesc(User provider);
    List<ProviderMedia> findByProviderAndType(User provider, ProviderMedia.MediaType type);
    Optional<ProviderMedia> findFirstByProviderAndTypeOrderByUploadedAtDesc(User provider, ProviderMedia.MediaType type);
}
