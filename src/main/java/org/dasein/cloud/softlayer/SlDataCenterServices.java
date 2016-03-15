package org.dasein.cloud.softlayer;

import com.softlayer.api.ApiException;
import com.softlayer.api.service.Location;
import com.softlayer.api.service.container.virtual.guest.Configuration;
import com.softlayer.api.service.container.virtual.guest.configuration.Option;
import com.softlayer.api.service.location.Datacenter;
import com.softlayer.api.service.virtual.Guest;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.dc.*;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Region and datacenter services for IBM Softlayer. The caveat is the region and the datacenter are the same thing so 
 * there is always one datacenter per region returned and its providerDataCenterId is the same as its region's 
 * providerRegionId.
 * 
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.10
 */
public class SlDataCenterServices extends AbstractDataCenterServices<SoftLayerCloud> {
    
    protected SlDataCenterServices(SoftLayerCloud provider) {
        super(provider);
    }
    private static final List<String> EU = Arrays.asList(
            "AT", "BE", "BG", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HR", "HU", "IE", 
            "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB"
    );
    
    @Override
    public @Nullable DataCenter getDataCenter(@Nonnull String providerDataCenterId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "dc.getDataCenter");
        try {
            for (DataCenter dc : listDataCenters(providerDataCenterId)) {
                if (dc.getProviderDataCenterId().equalsIgnoreCase(providerDataCenterId)) {
                    return dc;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nullable Region getRegion(@Nonnull String providerRegionId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "dc.getRegion");
        try {
            for (Region r : listRegions()) {
                if (r.getProviderRegionId().equalsIgnoreCase(providerRegionId)) {
                    return r;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nonnull Iterable<DataCenter> listDataCenters(@Nonnull String providerRegionId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "dc.listDataCenters");
        try {
            Iterable<Region> regions = listRegions();
            for( Region r : regions ) {
                if( r.getProviderRegionId().equalsIgnoreCase(providerRegionId) ) {
                    return Collections.singleton(new DataCenter(r.getProviderRegionId(), r.getName(), r.getProviderRegionId(), true, r.isAvailable()));
                }
            }
            return Collections.EMPTY_LIST;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nonnull Iterable<Region> listRegions() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "dc.listRegions");
        try {
            Cache<Region> cache = Cache.getInstance(getProvider(), "regions", Region.class, CacheLevel.CLOUD_ACCOUNT);
            Collection<Region> regionsCache = (Collection<Region>) cache.get(getContext());
            if (regionsCache != null) {
                return regionsCache;
            }

            Datacenter.Service dcService = getProvider().getClient().createService(com.softlayer.api.service.location.Datacenter.Service.class, "");

            dcService.withMask().id().name().longName();
            Map<String, Region> regions = new HashMap<>();
            dcService.setMask("mask[id, regions, name, longName, locationAddress]");
            
            
            for (Location loc : dcService.getViewableDatacenters()) {
                Region region = new Region(loc.getName(), loc.getLongName(), true, false);
                if (loc.getLocationAddress() == null) {
                    continue; // those with address null don't show up in the direct console either, filter them out
                }
                // TODO(stas): dal01 datacenter is returned by us but not shown in direct console, investigate
                String country = loc.getLocationAddress().getCountry();
                if( EU.contains(country) ) {
                    country = "EU";
                }
                region.setJurisdiction(country);
                regions.put(loc.getName(), region);
            }
            // check availability against the launch options
            Guest.Service guest = getProvider().getClient().createService(com.softlayer.api.service.virtual.Guest.Service.class, "");
            Configuration conf = guest.getCreateObjectOptions();
            for (Option opt : conf.getDatacenters()) {
                regions.get(opt.getTemplate().getDatacenter().getName()).setAvailable(true);
            }
            cache.put(getContext(), regions.values());
            return regions.values();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (ApiException.Internal e) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nonnull Iterable<ResourcePool> listResourcePools(@Nullable String providerDataCenterId) throws InternalException, CloudException {
        return Collections.EMPTY_LIST; // TODO
    }

    private volatile transient SlDataCenterCapabilities capabilities;
    
    @Override
    public @Nonnull DataCenterCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new SlDataCenterCapabilities(getProvider());
        }
        return capabilities;
    }
}
