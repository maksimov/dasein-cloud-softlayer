package org.dasein.cloud.softlayer;

import org.dasein.cloud.AbstractCapabilities;
import org.dasein.cloud.dc.DataCenterCapabilities;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.09
 */
public class SlDataCenterCapabilities extends AbstractCapabilities<SoftLayerCloud> implements DataCenterCapabilities {
    public SlDataCenterCapabilities(@Nonnull SoftLayerCloud provider) {
        super(provider);
    }

    @Override
    public String getProviderTermForDataCenter(Locale locale) {
        return "datacenter";
    }

    @Override
    public String getProviderTermForRegion(Locale locale) {
        return "location";
    }

    @Override
    public boolean supportsAffinityGroups() {
        return false; // TODO
    }

    @Override
    public boolean supportsResourcePools() {
        return false; // TODO
    }

    @Override
    public boolean supportsStoragePools() {
        return false; // TODO
    }

    @Override
    public boolean supportsFolders() {
        return false; // TODO
    }
}
