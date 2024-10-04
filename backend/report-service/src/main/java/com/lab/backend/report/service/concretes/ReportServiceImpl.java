package com.lab.backend.report.service.concretes;

import com.lab.backend.report.dto.requests.CreateReportRequest;
import com.lab.backend.report.dto.requests.UpdateReportRequest;
import com.lab.backend.report.dto.responses.GetPatientResponse;
import com.lab.backend.report.dto.responses.GetReportResponse;
import com.lab.backend.report.dto.responses.PagedResponse;
import com.lab.backend.report.entity.Report;
import com.lab.backend.report.repository.ReportRepository;
import com.lab.backend.report.repository.ReportSpecification;
import com.lab.backend.report.service.abstracts.ReportService;
import com.lab.backend.report.utilities.PdfUtil;
import com.lab.backend.report.utilities.exceptions.*;
import com.lab.backend.report.utilities.mappers.ReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.http.HttpHeaders;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service implementation for managing reports.
 *
 * @author Ömer Asaf BALIKÇI
 */

@Service
@Transactional
@RequiredArgsConstructor
@Log4j2
public class ReportServiceImpl implements ReportService {
    private final ReportRepository reportRepository;
    private final ReportMapper reportMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final String jwt = HttpHeaders.AUTHORIZATION.substring(7);
    private final PdfUtil pdfUtil;
    private final PrescriptionService prescriptionService;
    private final MailService mailService;

    /**
     * Retrieves a report by its ID.
     *
     * @param id the ID of the report
     * @return GetReportResponse containing the report details
     * @throws ReportNotFoundException if the report is not found
     */
    @Override
    public GetReportResponse getReportById(Long id) {
        log.debug("Fetching report with ID: {}", id);
        Report report = this.reportRepository.findByIdAndDeletedFalse(id).orElseThrow(() -> {
            log.error("Report not found with ID: {}", id);
            return new ReportNotFoundException("Report not found with id: " + id);
        });
        GetReportResponse response = this.reportMapper.toGetReportResponse(report);
        log.info("Report found: {}", response);
        return response;
    }

    /**
     * Retrieves all reports filtered and sorted.
     *
     * @param page              the page number
     * @param size              the page size
     * @param sortBy            the attribute to sort by
     * @param direction         the sort direction (ASC or DESC)
     * @param fileNumber        the file number to filter by
     * @param patientTrIdNumber the TR ID number of the patient
     * @param diagnosisTitle    the diagnosis title to filter by
     * @param diagnosisDetails  the diagnosis details to filter by
     * @param date              the report date to filter by
     * @param photoPath         the photo path to filter by
     * @param deleted           whether to include deleted reports
     * @return PagedResponse containing the list of reports
     */
    @Override
    public PagedResponse<GetReportResponse> getAllReportsFilteredAndSorted(int page, int size, String sortBy, String direction, String fileNumber,
                                                                           String patientTrIdNumber, String diagnosisTitle, String diagnosisDetails, String date,
                                                                           String photoPath, Boolean deleted) {
        log.debug("Fetching all reports with filters - Page: {}, Size: {}, SortBy: {}, Direction: {}", page, size, sortBy, direction);
        Pageable pagingSort = PageRequest.of(page, size, Sort.Direction.valueOf(direction.toUpperCase()), sortBy);
        ReportSpecification specification = new ReportSpecification(fileNumber, patientTrIdNumber, diagnosisTitle, diagnosisDetails, date, photoPath, deleted);
        Page<Report> userPage = this.reportRepository.findAll(specification, pagingSort);
        List<GetReportResponse> reportResponses = userPage.getContent()
                .stream()
                .map(this.reportMapper::toGetReportResponse)
                .toList();
        log.info("Total reports found: {}", userPage.getTotalElements());
        return new PagedResponse<>(
                reportResponses,
                userPage.getNumber(),
                userPage.getTotalPages(),
                userPage.getTotalElements(),
                userPage.getSize(),
                userPage.isFirst(),
                userPage.isLast(),
                userPage.hasNext(),
                userPage.hasPrevious()
        );
    }

    /**
     * Retrieves reports created by a specific technician.
     *
     * @param username          the username of the technician
     * @param page              the page number
     * @param size              the page size
     * @param sortBy            the attribute to sort by
     * @param direction         the sort direction (ASC or DESC)
     * @param fileNumber        the file number to filter by
     * @param patientTrIdNumber the TR ID number of the patient
     * @param diagnosisTitle    the diagnosis title to filter by
     * @param diagnosisDetails  the diagnosis details to filter by
     * @param date              the report date to filter by
     * @param photoPath         the photo path to filter by
     * @param deleted           whether to include deleted reports
     * @return PagedResponse containing the list of reports
     */
    @Override
    public PagedResponse<GetReportResponse> getReportsByTechnician(String username, int page, int size, String sortBy, String direction, String fileNumber,
                                                                   String patientTrIdNumber, String diagnosisTitle, String diagnosisDetails, String date,
                                                                   String photoPath, Boolean deleted) {
        log.debug("Fetching reports for technician: {} with filters - Page: {}, Size: {}, SortBy: {}, Direction: {}", username, page, size, sortBy, direction);
        Pageable pagingSort = PageRequest.of(page, size, Sort.Direction.valueOf(direction.toUpperCase()), sortBy);
        ReportSpecification specification = new ReportSpecification(username, fileNumber, patientTrIdNumber, diagnosisTitle, diagnosisDetails, date, photoPath, deleted);
        Page<Report> userPage = this.reportRepository.findAll(specification, pagingSort);
        List<GetReportResponse> reportResponses = userPage.getContent()
                .stream()
                .map(this.reportMapper::toGetReportResponse)
                .toList();
        log.info("Total reports found for technician {}: {}", username, userPage.getTotalElements());
        return new PagedResponse<>(
                reportResponses,
                userPage.getNumber(),
                userPage.getTotalPages(),
                userPage.getTotalElements(),
                userPage.getSize(),
                userPage.isFirst(),
                userPage.isLast(),
                userPage.hasNext(),
                userPage.hasPrevious()
        );
    }

    /**
     * Checks the patient record.
     *
     * @param username   the username of the person making the request
     * @param trIdNumber the Turkish Republic ID number to be checked
     * @return a message indicating whether the TR ID number is valid or not
     * @throws InvalidTrIdNumberException if the TR ID number format is invalid
     * @throws PatientNotFoundException   if no patient is found for the given TR ID number
     * @throws UnexpectedException        for any unexpected errors during the operation
     */
    @Override
    public String checkTrIdNumber(String username, String trIdNumber) {
        String trIdRegex = "^[1-9][0-9]{10}$";
        Pattern pattern = Pattern.compile(trIdRegex);
        Matcher matcher = pattern.matcher(trIdNumber);
        if (!matcher.matches()) {
            log.error("Invalid TR ID number format: {}", trIdNumber);
            throw new InvalidTrIdNumberException("Invalid TR ID number format");
        }
        Boolean check;
        try {
            check = this.webClientBuilder.build().get()
                    .uri("http://patient-service/patients/check-tr-id-number", uriBuilder ->
                            uriBuilder.queryParam("trIdNumber", trIdNumber).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
        } catch (WebClientResponseException.NotFound ex) {
            log.error("Patient not found in patient service for TR ID number: {}", trIdNumber);
            throw new PatientNotFoundException("Patient not found in patient service with TR ID number: " + trIdNumber);
        } catch (Exception e) {
            log.error("Unexpected exception occurred: {}", e.getMessage());
            throw new UnexpectedException("An unexpected error occurred: " + e.getMessage());
        }

        if (check != null && check) {
            this.redisTemplate.delete("validTcForReport:" + username);
            this.redisTemplate.opsForValue().set("validTcForReport:" + username, trIdNumber, 1, TimeUnit.HOURS);
            log.info("TR ID number {} is valid for user {}. Redirecting to report creation...", trIdNumber, username);
            return "TR ID number is valid. Redirecting to report creation...";
        } else {
            log.error("TR ID number {} is invalid or not found for user {}.", trIdNumber, username);
            return "TR ID number is invalid or not found.";
        }
    }

    /**
     * Adds a new report.
     *
     * @param username            the username of the technician creating the report
     * @param createReportRequest the request object containing the report data
     * @return GetReportResponse containing the created report details
     * @throws InvalidTrIdNumberException if the TR ID number is not found
     */
    @Override
    public GetReportResponse addReport(String username, CreateReportRequest createReportRequest) {
        log.debug("Adding report for user: {}", username);
        String trIdNumber = this.redisTemplate.opsForValue().get("validTcForReport:" + username);
        if (trIdNumber != null) {
            Report report = this.reportMapper.toReport(createReportRequest);
            report.setPatientTrIdNumber(trIdNumber);
            report.setTechnicianUsername(username);
            this.reportRepository.save(report);
            this.redisTemplate.delete("validTcForReport:" + username);
            GetReportResponse response = this.reportMapper.toGetReportResponse(report);
            log.info("Report added successfully: {}", response);
            return response;
        } else {
            log.error("TR ID number not found in Redis for user: {}", username);
            throw new InvalidTrIdNumberException("TR ID number not found in Redis, please validate first.");
        }
    }

    /**
     * Updates an existing report with new information.
     *
     * @param username            The username of the technician performing the update.
     * @param updateReportRequest The update request containing new report data.
     * @return The updated GetReportResponse object.
     * @throws ReportNotFoundException     if the report doesn't exist or is deleted.
     * @throws UnauthorizedAccessException if the user is not authorized to update the report.
     */
    @Override
    public GetReportResponse updateReport(String username, UpdateReportRequest updateReportRequest) {
        log.debug("Updating report with id: {} by technician: {}", updateReportRequest.getId(), username);
        Report existingReport = this.reportRepository.findByIdAndDeletedFalse(updateReportRequest.getId())
                .orElseThrow(() -> {
                    log.error("Report with id {} not found", updateReportRequest.getId());
                    return new ReportNotFoundException("Report doesn't exist with id " + updateReportRequest.getId());
                });

        if (!username.equals(existingReport.getTechnicianUsername())) {
            log.error("Unauthorized update attempt by user: {} on report id: {}", username, updateReportRequest.getId());
            throw new UnauthorizedAccessException("You are not authorized to update this report.");
        }

        if (updateReportRequest.getDiagnosisTitle() != null && !existingReport.getDiagnosisTitle().equals(updateReportRequest.getDiagnosisTitle())) {
            existingReport.setDiagnosisTitle(updateReportRequest.getDiagnosisTitle());
        }
        if (updateReportRequest.getDiagnosisDetails() != null && !existingReport.getDiagnosisDetails().equals(updateReportRequest.getDiagnosisDetails())) {
            existingReport.setDiagnosisDetails(updateReportRequest.getDiagnosisDetails());
        }
        existingReport.setDate(new Date());
        this.reportRepository.save(existingReport);
        GetReportResponse response = this.reportMapper.toGetReportResponse(existingReport);
        log.info("Report with id: {} successfully updated by technician: {}", updateReportRequest.getId(), username);
        return response;
    }

    /**
     * Soft deletes a report.
     *
     * @param username The username of the technician performing the deletion.
     * @param id       The ID of the report to delete.
     * @throws ReportNotFoundException     if the report doesn't exist or is deleted.
     * @throws UnauthorizedAccessException if the user is not authorized to delete the report.
     */
    @Override
    public void deleteReport(String username, Long id) {
        log.debug("Attempting to delete report with id: {} by technician: {}", id, username);
        Report report = this.reportRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> {
                    log.error("The report with ID {} could not be found for deletion.", id);
                    return new ReportNotFoundException("Report doesn't exist with id " + id);
                });
        if (!username.equals(report.getTechnicianUsername())) {
            log.error("Unauthorized delete attempt by user: {} on report id: {}", username, id);
            throw new UnauthorizedAccessException("You are not authorized to delete this report.");
        }
        report.setDeleted(true);
        this.reportRepository.save(report);
        log.info("Report with id: {} successfully deleted by technician: {}", id, username);
    }

    /**
     * Restores a soft-deleted report.
     *
     * @param username The username of the technician performing the restoration.
     * @param id       The ID of the report to restore.
     * @return The restored GetReportResponse object.
     * @throws ReportNotFoundException     if the report doesn't exist or is not deleted.
     * @throws UnauthorizedAccessException if the user is not authorized to restore the report.
     */
    @Override
    public GetReportResponse restoreReport(String username, Long id) {
        log.debug("Attempting to restore report with id: {} by technician: {}", id, username);
        Report report = this.reportRepository.findByIdAndDeletedTrue(id)
                .orElseThrow(() -> {
                    log.error("The report with ID {} could not be found to restore", id);
                    return new ReportNotFoundException("Report doesn't exist with id " + id);
                });
        if (!username.equals(report.getTechnicianUsername())) {
            log.error("Unauthorized restore attempt by user: {} on report id: {}", username, id);
            throw new UnauthorizedAccessException("You are not authorized to restore this report.");
        }
        report.setDeleted(false);
        this.reportRepository.save(report);
        GetReportResponse response = this.reportMapper.toGetReportResponse(report);
        log.info("Report with id: {} successfully restored by technician: {}", id, username);
        return response;
    }

    /**
     * Adds a photo to the report.
     *
     * @param username The username of the technician adding the photo.
     * @param reportId The ID of the report to add the photo to.
     * @param photo    The MultipartFile representing the photo to upload.
     * @throws ReportNotFoundException     if the report doesn't exist or is deleted.
     * @throws UnauthorizedAccessException if the user is not authorized to modify the report.
     * @throws FileStorageException        if the photo could not be stored due to an I/O error.
     */
    @Override
    public void addPhoto(String username, Long reportId, MultipartFile photo) {
        log.debug("Adding photo to report with id: {} by technician: {}", reportId, username);
        Report report = this.reportRepository.findByIdAndDeletedFalse(reportId)
                .orElseThrow(() -> {
                    log.error("Report with id {} not found for add photo", reportId);
                    return new ReportNotFoundException("Report doesn't exist with id " + reportId);
                });
        if (!username.equals(report.getTechnicianUsername())) {
            log.error("Unauthorized photo upload attempt by user: {} on report id: {}", username, reportId);
            throw new UnauthorizedAccessException("You are not authorized to restore this report.");
        }
        String uploadDir = "backend/uploads/photos/";
        String originalFileName = StringUtils.cleanPath(Objects.requireNonNull(photo.getOriginalFilename()));
        String fileExtension = getFileExtension(originalFileName);
        String newFileName = UUID.randomUUID() + "." + fileExtension;
        try {
            Path uploadPath = Paths.get(uploadDir + newFileName);
            Files.createDirectories(uploadPath.getParent());
            Files.copy(photo.getInputStream(), uploadPath, StandardCopyOption.REPLACE_EXISTING);
            report.setPhotoPath(uploadPath.toString());
            this.reportRepository.save(report);
            log.info("Successfully added photo to report with id: {}", reportId);
        } catch (IOException exception) {
            log.error("Error occurred while uploading photo for report id: {}", reportId, exception);
            throw new FileStorageException("Could not store file " + newFileName + ". Please try again! " + exception);
        }
    }

    // Helper method to extract file extension.
    private String getFileExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    /**
     * Retrieves the photo associated with the report.
     *
     * @param username The username of the technician requesting the photo.
     * @param reportId The ID of the report.
     * @return The photo as a byte array.
     * @throws ReportNotFoundException     if the report is not found.
     * @throws UnauthorizedAccessException if the technician is not authorized to access the report.
     * @throws FileStorageException        if the photo cannot be found or read.
     */
    @Override
    public byte[] getPhoto(String username, Long reportId) {
        log.debug("Fetching photo for report with id: {} by technician: {}", reportId, username);
        Report report = this.reportRepository.findByIdAndDeletedFalse(reportId)
                .orElseThrow(() -> {
                    log.error("Report with id: {} not found for get photo", reportId);
                    return new ReportNotFoundException("Report doesn't exist with id " + reportId);
                });
        if (!username.equals(report.getTechnicianUsername())) {
            log.error("Unauthorized access attempt by username: {} for report id: {}", username, reportId);
            throw new UnauthorizedAccessException("You are not authorized to restore this report.");
        }
        String photoPath = report.getPhotoPath();
        if (photoPath == null || photoPath.isEmpty()) {
            log.error("No photo found for report id: {}", reportId);
            throw new FileStorageException("No photo found for report with id " + reportId);
        }
        try {
            log.debug("Reading photo from path: {}", photoPath);
            Path filePath = Paths.get(photoPath);
            return Files.readAllBytes(filePath);
        } catch (IOException exception) {
            log.error("Could not read file at path: {} for report id: {}", photoPath, reportId, exception);
            throw new FileStorageException("Could not read file: " + photoPath + ". Please try again! " + exception);
        }
    }

    /**
     * Deletes the photo associated with the report.
     *
     * @param username The username of the technician requesting the deletion.
     * @param reportId The ID of the report.
     * @throws ReportNotFoundException     if the report is not found.
     * @throws UnauthorizedAccessException if the technician is not authorized to delete the photo.
     * @throws FileStorageException        if the photo cannot be found or deleted.
     */
    @Override
    public void deletePhoto(String username, Long reportId) {
        log.debug("Attempting to delete photo for report id: {} by technician: {}", reportId, username);
        Report report = this.reportRepository.findByIdAndDeletedFalse(reportId)
                .orElseThrow(() -> {
                    log.error("Report with id: {} not found for delete photo", reportId);
                    return new ReportNotFoundException("Report doesn't exist with id " + reportId);
                });
        if (!username.equals(report.getTechnicianUsername())) {
            log.error("Unauthorized deletion attempt by username: {} for report id: {}", username, reportId);
            throw new UnauthorizedAccessException("You are not authorized to restore this report.");
        }
        String photoPath = report.getPhotoPath();
        if (photoPath == null || photoPath.isEmpty()) {
            log.error("No photo to delete for report id: {}", reportId);
            throw new FileStorageException("No photo to delete for report with id " + reportId);
        }
        try {
            log.debug("Deleting photo at path: {}", photoPath);
            Path filePath = Paths.get(photoPath);
            Files.deleteIfExists(filePath);
            report.setPhotoPath(null);
            this.reportRepository.save(report);
            log.info("Successfully deleted photo for report id: {}", reportId);
        } catch (IOException exception) {
            log.error("Could not delete file at path: {} for report id: {}", photoPath, reportId, exception);
            throw new FileStorageException("Could not delete file: " + photoPath + ". Please try again! " + exception);
        }
    }

    /**
     * Retrieves patient information based on their TR ID number.
     *
     * @param jwt        The JWT for authorization.
     * @param trIdNumber The TR ID number of the patient.
     * @return A Mono containing the GetPatientResponse.
     * @throws InvalidTrIdNumberException if the TR ID number format is invalid.
     * @throws PatientNotFoundException   if the patient is not found.
     */
    private Mono<GetPatientResponse> getPatientByTrIdNumber(String jwt, String trIdNumber) {
        log.debug("Fetching patient information for TR ID number: {}", trIdNumber);
        String trIdRegex = "^[1-9][0-9]{10}$";
        Pattern pattern = Pattern.compile(trIdRegex);
        Matcher matcher = pattern.matcher(trIdNumber);
        if (!matcher.matches()) {
            log.error("Invalid TR ID number: {}", trIdNumber);
            throw new InvalidTrIdNumberException("Invalid TR ID number format");
        }

        return this.webClientBuilder.build().get()
                .uri("http://patient-service/patients/tr-id-number", uriBuilder ->
                        uriBuilder.queryParam("trIdNumber", trIdNumber).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                    log.error("Client error while fetching patient information for TR ID number: {}", trIdNumber);
                    return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(new PatientNotFoundException("Client error: " + errorBody)));
                })
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                    log.error("Server error while fetching patient information for TR ID number: {}", trIdNumber);
                    return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(new UnexpectedException("Server error: " + errorBody)));
                })
                .bodyToMono(GetPatientResponse.class);
    }

    /**
     * Generates a PDF report based on the reportId.
     *
     * @param reportId The ID of the report.
     * @return A Mono containing the generated PDF as a byte array.
     * @throws ReportNotFoundException if the report is not found.
     */
    @Override
    public Mono<byte[]> getReportPdf(Long reportId) {
        log.debug("Generating PDF for report id: {}", reportId);
        Report report = this.reportRepository.findByIdAndDeletedFalse(reportId).orElseThrow(() -> {
            log.error("Report with id: {} not found", reportId);
            return new ReportNotFoundException("Report not found with id: " + reportId);
        });
        GetReportResponse reportResponse = this.reportMapper.toGetReportResponse(report);
        return getPatientByTrIdNumber(jwt, reportResponse.getPatientTrIdNumber())
                .flatMap(patientResponse -> {
                    byte[] pdfBytes = this.pdfUtil.generatePdf(reportResponse, patientResponse);
                    log.info("Successfully generated PDF for report id: {}", reportId);
                    return Mono.just(pdfBytes);
                });
    }

    /**
     * Retrieves the prescription for the report and caches it.
     *
     * @param username The username of the technician.
     * @param reportId The ID of the report.
     * @return The generated prescription as a byte array.
     * @throws ReportNotFoundException if the report is not found.
     * @throws FileStorageException    if an error occurs during file operations.
     */
    @Override
    public byte[] getPrescription(String username, Long reportId) {
        log.debug("Generating prescription for report id: {}", reportId);
        Report report = this.reportRepository.findByIdAndDeletedFalse(reportId)
                .orElseThrow(() -> {
                    log.error("Report with id: {} not found for prescription", reportId);
                    return new ReportNotFoundException("Report doesn't exist with id " + reportId);
                });
        byte[] pdfBytes = this.prescriptionService.generatePrescription(report.getDiagnosisDetails());
        this.redisTemplate.delete("prescription:" + username);
        this.redisTemplate.delete("trIdNumber:" + username);
        this.redisTemplate.opsForValue().set("prescription:" + username, Base64.getEncoder().encodeToString(pdfBytes), 1, TimeUnit.HOURS);
        this.redisTemplate.opsForValue().set("trIdNumber:" + username, report.getPatientTrIdNumber(), 1, TimeUnit.HOURS);
        log.info("Successfully generated and cached prescription for username: {}", username);
        return pdfBytes;
    }

    /**
     * Sends the cached prescription to the patient's email.
     *
     * @param username The username of the technician.
     * @throws UnexpectedException if an error occurs during email sending.
     */
    @Override
    public void sendPrescription(String username) {
        log.debug("Sending prescription email for username: {}", username);
        String encodedPrescription = this.redisTemplate.opsForValue().get("prescription:" + username);
        String encodedTrIdNumber = this.redisTemplate.opsForValue().get("trIdNumber:" + username);
        if (encodedPrescription != null && encodedTrIdNumber != null) {
            byte[] pdfBytes = Base64.getDecoder().decode(encodedPrescription);
            if (pdfBytes == null || pdfBytes.length == 0) {
                log.error("PDF data is empty or corrupted for username: {}", username);
                throw new UnexpectedException("PDF data is empty or corrupted");
            }
            String email = getEmail(jwt, encodedTrIdNumber);
            this.mailService.sendEmail(email, "Your Prescription", "Here is your prescription.", pdfBytes, "prescription.pdf");
            this.redisTemplate.delete("prescription:" + username);
            this.redisTemplate.delete("trIdNumber:" + username);
            log.info("Successfully sent prescription email to: {}", email);
        } else {
            log.error("Cached prescription or TR ID number not found for username: {}", username);
            throw new UnexpectedException("Prescription or TR ID number null in Redis");
        }
    }

    /**
     * Retrieves the email of a patient from the patient service using the patient's TR ID number.
     *
     * @param jwt        The JWT token for authorization.
     * @param trIdNumber The TR ID number of the patient.
     * @return The email of the patient.
     * @throws PatientNotFoundException if the patient is not found in the patient service.
     * @throws UnexpectedException      if an error occurs while calling the patient service.
     * @throws EmailNullException       if the email retrieved is null or empty.
     */
    private String getEmail(String jwt, String trIdNumber) {
        log.trace("Entering getEmail method with TR ID number: {}", trIdNumber);
        String email;
        try {
            log.debug("Calling patient service to retrieve email for TR ID number: {}", trIdNumber);
            email = this.webClientBuilder.build().get()
                    .uri("http://patient-service/patients/email", uriBuilder ->
                            uriBuilder.queryParam("trIdNumber", trIdNumber).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("Received email: {} for TR ID number: {}", email, trIdNumber);
        } catch (WebClientResponseException.NotFound ex) {
            log.error("Patient not found in patient service for TR ID number: {}", trIdNumber, ex);
            throw new PatientNotFoundException("Patient not found in patient service with TR ID number: " + trIdNumber);
        } catch (Exception e) {
            log.error("Unexpected error occurred while calling patient service for TR ID number: {}", trIdNumber, e);
            throw new UnexpectedException("Error occurred while calling patient service: " + e);
        }
        if (email != null) {
            log.info("Successfully retrieved email for TR ID number: {}", trIdNumber);
            return email;
        } else {
            log.error("Email is null or empty for TR ID number: {}", trIdNumber);
            throw new EmailNullException("Email is null or empty");
        }
    }
}
