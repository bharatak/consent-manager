package in.projecteka.consentmanager.consent;

import in.projecteka.consentmanager.consent.model.*;
import in.projecteka.consentmanager.consent.model.response.ConsentArtefactRepresentation;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.*;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.stream.StreamSupport;

@AllArgsConstructor
public class ConsentArtefactRepository {
    private static final String INSERT_CONSENT_ARTEFACT_QUERY = "INSERT INTO consent_artefact" +
            " (consent_request_id, consent_artefact_id, patient_id, consent_artefact, signature, status) VALUES" +
            " ($1, $2, $3, $4, $5, $6)";
    private static final String INSERT_HIP_CONSENT_ARTEFACT_QUERY = "INSERT INTO hip_consent_artefact" +
            " (consent_request_id, consent_artefact_id, patient_id, consent_artefact, signature, status) VALUES" +
            " ($1, $2, $3, $4, $5, $6)";
    private static final String FAILED_TO_SAVE_CONSENT_ARTEFACT = "Failed to save consent artefact";
    private static final String UPDATE_CONSENT_REQUEST_STATUS_QUERY = "UPDATE consent_request SET status=$1, " +
            "date_modified=$2 WHERE request_id=$3";
    private static final String UNKNOWN_ERROR_OCCURRED = "Unknown error occurred";
    private static final String SELECT_CONSENT_QUERY = "SELECT status, consent_artefact, signature " +
            "FROM consent_artefact WHERE consent_artefact_id = $1";
    private static final String SELECT_CONSENT_WITH_REQUEST_QUERY = "SELECT status, consent_artefact, consent_request_id, date_modified " +
            "FROM consent_artefact WHERE consent_artefact_id = $1";
    private static final String SELECT_HIP_CONSENT_QUERY = "SELECT status, consent_artefact, signature " +
            "FROM hip_consent_artefact WHERE consent_artefact_id = $1";
    private static final String SELECT_CONSENT_IDS_FROM_CONSENT_ARTEFACT = "SELECT consent_artefact_id " +
            "FROM consent_artefact WHERE consent_request_id=$1";
    private static final String SELECT_CONSENTS_TO_VALIDATE_EXPIRY = "SELECT consent_artefact_id, consent_artefact, patient_id " +
            "FROM consent_artefact WHERE status=$1";
    private static final String UPDATE_CONSENT_ARTEFACT_STATUS_QUERY = "UPDATE consent_artefact SET status=$1, " +
            "date_modified=$2 WHERE consent_artefact_id=$3";
    private static final String FAILED_TO_RETRIEVE_CA = "Failed to retrieve Consent Artifact.";

    private PgPool dbClient;

    public Mono<Void> addConsentArtefactAndUpdateStatus(ConsentArtefact consentArtefact,
                                                        String consentRequestId,
                                                        String patientId,
                                                        String signature,
                                                        HIPConsentArtefactRepresentation hipConsentArtefact) {
        return Mono.create(monoSink -> dbClient.getConnection(connection -> {
                    if (connection.failed()) {
                        monoSink.error(new RuntimeException("Error getting connection to database."));
                        return;
                    }
                    SqlConnection sqlConnection = connection.result();
                    if (sqlConnection == null) {
                        monoSink.error(new RuntimeException("Error getting connection to database."));
                        return;
                    }
                    Transaction transaction = sqlConnection.begin();
                    transaction.preparedQuery(INSERT_CONSENT_ARTEFACT_QUERY)
                            .execute(Tuple.of(consentRequestId,
                                    consentArtefact.getConsentId(),
                                    patientId,
                                    JsonObject.mapFrom(consentArtefact),
                                    signature,
                                    ConsentStatus.GRANTED.toString()),
                                    insertConsentArtefactHandler -> {
                                        if (insertConsentArtefactHandler.failed()) {
                                            sqlConnection.close();
                                            monoSink.error(new Exception(FAILED_TO_SAVE_CONSENT_ARTEFACT));
                                            return;
                                        }
                                        transaction.preparedQuery(INSERT_HIP_CONSENT_ARTEFACT_QUERY)
                                                .execute(Tuple.of(consentRequestId,
                                                        hipConsentArtefact.getConsentDetail().getConsentId(),
                                                        patientId,
                                                        JsonObject.mapFrom(hipConsentArtefact.getConsentDetail()),
                                                        signature,
                                                        ConsentStatus.GRANTED.toString()),
                                                        insertHipConsentArtefactHandler -> {
                                                            if (insertHipConsentArtefactHandler.failed()) {
                                                                sqlConnection.close();
                                                                monoSink.error(
                                                                        new Exception(FAILED_TO_SAVE_CONSENT_ARTEFACT));
                                                                return;
                                                            }
                                                            transaction.preparedQuery(
                                                                    UPDATE_CONSENT_REQUEST_STATUS_QUERY)
                                                                    .execute(Tuple.of(ConsentStatus.GRANTED.toString(),
                                                                            LocalDateTime.now(), consentRequestId),
                                                                            updateConsentRequestHandler -> {
                                                                                if (updateConsentRequestHandler
                                                                                        .failed()) {
                                                                                    sqlConnection.close();
                                                                                    monoSink.error(new Exception(UNKNOWN_ERROR_OCCURRED));
                                                                                    return;
                                                                                }
                                                                                transaction.commit();
                                                                                monoSink.success();
                                                                            });
                                                        }
                                                );
                                    });
                })
        );
    }

    public Mono<ConsentArtefactRepresentation> getConsentArtefact(String consentId) {
        return Mono.create(monoSink -> dbClient.preparedQuery(SELECT_CONSENT_QUERY)
                .execute(Tuple.of(consentId),
                        handler -> {
                            if (handler.failed()) {
                                monoSink.error(new RuntimeException(FAILED_TO_RETRIEVE_CA, handler.cause()));
                            } else {
                                RowSet<Row> results = handler.result();
                                if (results.iterator().hasNext()) {
                                    Row row = results.iterator().next();
                                    JsonObject artefact = (JsonObject) row.getValue("consent_artefact");
                                    ConsentArtefact consentArtefact = artefact.mapTo(ConsentArtefact.class);
                                    ConsentArtefactRepresentation representation = ConsentArtefactRepresentation
                                            .builder()
                                            .status(ConsentStatus.valueOf(row.getString("status")))
                                            .consentDetail(consentArtefact)
                                            .signature(row.getString("signature"))
                                            .build();
                                    monoSink.success(representation);
                                } else {
                                    monoSink.success(null);
                                }
                            }
                        }));
    }

    public Mono<HIPConsentArtefactRepresentation> getHipConsentArtefact(String consentId) {
        return Mono.create(monoSink -> dbClient.preparedQuery(SELECT_HIP_CONSENT_QUERY)
                .execute(Tuple.of(consentId),
                        handler -> {
                            if (handler.failed()) {
                                monoSink.error(new RuntimeException(FAILED_TO_RETRIEVE_CA, handler.cause()));
                            } else {
                                RowSet<Row> results = handler.result();
                                if (results.iterator().hasNext()) {
                                    Row row = results.iterator().next();
                                    JsonObject artefact = (JsonObject) row.getValue("consent_artefact");
                                    HIPConsentArtefact consentArtefact = artefact.mapTo(HIPConsentArtefact.class);
                                    HIPConsentArtefactRepresentation representation = HIPConsentArtefactRepresentation
                                            .builder()
                                            .status(ConsentStatus.valueOf(row.getString("status")))
                                            .consentDetail(consentArtefact)
                                            .signature(row.getString("signature"))
                                            .build();
                                    monoSink.success(representation);
                                } else {
                                    monoSink.success(null);
                                }
                            }
                        }));
    }

    public Flux<String> getConsentArtefacts(String consentRequestId) {
        return Flux.create(fluxSink -> dbClient.preparedQuery(SELECT_CONSENT_IDS_FROM_CONSENT_ARTEFACT)
                .execute(Tuple.of(consentRequestId),
                        handler -> {
                            if (handler.failed()) {
                                fluxSink.error(new Exception("Failed to get consent id from consent request Id"));
                            } else {
                                StreamSupport.stream(handler.result().spliterator(), false)
                                        .map(row -> row.getString("consent_artefact_id"))
                                        .forEach(fluxSink::next);
                                fluxSink.complete();
                            }
                        }));
    }

    @SneakyThrows
    public Flux<ConsentExpiry> getConsentArtefacts(ConsentStatus consentStatus) {
        return Flux.create(fluxSink -> dbClient.preparedQuery(SELECT_CONSENTS_TO_VALIDATE_EXPIRY)
                .execute(Tuple.of(consentStatus.toString()),
                        handler -> {
                            if (handler.failed()) {
                                fluxSink.error(new Exception("Failed to get GRANTED consents"));
                            } else {
                                StreamSupport.stream(handler.result().spliterator(), false)
                                        .map(row -> {
                                            JsonObject artefact = (JsonObject) row.getValue("consent_artefact");
                                            ConsentArtefact consentArtefact = artefact.mapTo(ConsentArtefact.class);
                                            return ConsentExpiry.builder()
                                                    .consentId(row.getString("consent_artefact_id"))
                                                    .patientId(row.getString("patient_id"))
                                                    .consentExpiryDate(consentArtefact.getPermission().getDataExpiryAt())
                                                    .build();
                                        })
                                        .forEach(fluxSink::next);
                                fluxSink.complete();
                            }
                        }));
    }

    public Mono<Void> updateStatus(String consentId, String consentRequestId, ConsentStatus status) {
        return Mono.create(monoSink -> dbClient.begin(res -> {
            if (res.succeeded()) {
                Transaction transaction = res.result();
                update(consentRequestId, status, monoSink, transaction, UPDATE_CONSENT_REQUEST_STATUS_QUERY);
                update(consentId, status, monoSink, transaction, UPDATE_CONSENT_ARTEFACT_STATUS_QUERY);
                transaction.commit();
                monoSink.success();
            } else {
                monoSink.error(new RuntimeException("Error connecting to database. "));
            }
        }));
    }

    public Mono<Void> updateConsentArtefactStatus(String consentId, ConsentStatus status) {
        return Mono.create(monoSink -> dbClient.preparedQuery(UPDATE_CONSENT_ARTEFACT_STATUS_QUERY)
                .execute(Tuple.of(status.toString(),
                        LocalDateTime.now(),
                        consentId),
                        updateHandler -> {
                            if (updateHandler.failed()) {
                                monoSink.error(new Exception("Failed to update consent artefact status"));
                            }
                            monoSink.success();
                        }));
    }

    private void update(String id,
                        ConsentStatus status,
                        MonoSink<Void> monoSink,
                        Transaction transaction,
                        String updateStatusQuery) {
        transaction.preparedQuery(updateStatusQuery)
                .execute(Tuple.of(status.toString(), LocalDateTime.now(), id),
                        updateHandler -> {
                            if (updateHandler.failed()) {
                                transaction.close();
                                monoSink.error(new Exception("Failed to update status"));
                            }
                        });
    }

    public Mono<ConsentRepresentation> getConsentWithRequest(String consentId) {
        return Mono.create(monoSink -> dbClient.preparedQuery(SELECT_CONSENT_WITH_REQUEST_QUERY)
                .execute(Tuple.of(consentId),
                        handler -> {
                            if (handler.failed()) {
                                monoSink.error(new RuntimeException(FAILED_TO_RETRIEVE_CA));
                                return;
                            }
                            RowSet<Row> results = handler.result();
                            ConsentRepresentation consentRepresentation = null;
                            for (Row result : results) {
                                consentRepresentation = mapToConsentRepresentation(result);
                            }
                            monoSink.success(consentRepresentation);
                        }));
    }

    private ConsentRepresentation mapToConsentRepresentation(Row result) {
        JsonObject artefact = (JsonObject) result.getValue("consent_artefact");
        ConsentArtefact consentArtefact = artefact.mapTo(ConsentArtefact.class);

        return ConsentRepresentation
                .builder()
                .status(ConsentStatus.valueOf(result.getString("status")))
                .consentDetail(consentArtefact)
                .consentRequestId(result.getString("consent_request_id"))
                .dateModified(convertToDate(result.getLocalDateTime("date_modified")))
                .build();
    }

    private Date convertToDate(LocalDateTime timestamp) {
        if (timestamp != null) {
            return Date.from(timestamp.atZone(ZoneId.systemDefault()).toInstant());
        }
        return null;
    }
}
