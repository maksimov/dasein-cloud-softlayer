package org.dasein.cloud.softlayer;

import com.softlayer.api.ApiException;
import com.softlayer.api.service.Account;
import com.softlayer.api.service.user.Customer;
import org.dasein.cloud.softlayer.compute.SlComputeServices;
import com.softlayer.api.ApiClient;
import com.softlayer.api.RestApiClient;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.ComputeServices;
import org.dasein.cloud.compute.VirtualMachineSupport;
import org.dasein.cloud.dc.DataCenterServices;
import org.dasein.cloud.util.APITrace;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.10
 */
public class SoftLayerCloud extends AbstractCloud {
    
    @Override
    public @Nonnull String getCloudName() {
        return "SoftLayer";
    }

    private SlDataCenterServices dcServices;
    private SlComputeServices computeServices;
    private ContextRequirements contextRequirements;
    
    @Override
    public @Nonnull DataCenterServices getDataCenterServices() {
        if( dcServices == null ) {
            dcServices = new SlDataCenterServices(this);
        }
        return dcServices;
    }
    
    @Override
    public @Nonnull String getProviderName() {
        return "IBM";
    }

    
    @Override
    public @Nullable ComputeServices getComputeServices() {
        if( computeServices == null ) {
            computeServices = new SlComputeServices(this);
        }
        return computeServices;
    }
    
    @Override
    public @Nonnull ContextRequirements getContextRequirements() {
        if( contextRequirements == null ) {
            contextRequirements = new ContextRequirements(
                    new ContextRequirements.Field("accessKey", "SoftLayer access key", ContextRequirements.FieldType.PASSWORD)
            );
        }
        return contextRequirements;
    }

    public ApiClient getClient() {
        String accessKey = null;
        for(ContextRequirements.Field f : getContextRequirements().getConfigurableValues() ) {
            if( f.type.equals(ContextRequirements.FieldType.PASSWORD) ){
                accessKey = new String((byte[]) getContext().getConfigurationValue(f));
            }
        }
        // TODO: can we cache the client in the class instance?
        return new RestApiClient().withCredentials(getContext().getAccountNumber(), accessKey);
    }
    
    @Override
    public String testContext() {
        APITrace.begin(this, "Cloud.testContext");
        try {
            ProviderContext ctx = getContext();
            if( ctx == null ) {
                return null;
            }
            Account.Service acct = getClient().createService(com.softlayer.api.service.Account.Service.class, "");
            if( acct == null ) {
                return null;
            }
            Customer customer = acct.getMasterUser();
            if( customer != null ) {
                return String.valueOf(customer.getAccountId());
            }
            return null;
        } catch (ApiException.Unauthorized e) {
            return null;
        } finally {
            APITrace.end();
        }
    }
}
