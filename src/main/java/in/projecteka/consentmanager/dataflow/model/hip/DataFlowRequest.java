package in.projecteka.consentmanager.dataflow.model.hip;

import in.projecteka.consentmanager.dataflow.model.Consent;
import in.projecteka.consentmanager.dataflow.model.HIDataRange;
import in.projecteka.consentmanager.dataflow.model.KeyMaterial;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataFlowRequest {
    private String transactionId;
    private Consent consent;
    private HIDataRange hiDataRange;
    private String dataPushUrl;
    private KeyMaterial keyMaterial;
}
