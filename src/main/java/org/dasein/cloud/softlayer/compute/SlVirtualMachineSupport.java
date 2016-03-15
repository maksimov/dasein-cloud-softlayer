package org.dasein.cloud.softlayer.compute;

import com.softlayer.api.ApiException;
import com.softlayer.api.service.Account;
import com.softlayer.api.service.Location;
import com.softlayer.api.service.container.virtual.guest.Configuration;
import com.softlayer.api.service.container.virtual.guest.configuration.Option;
import com.softlayer.api.service.dns.Domain;
import com.softlayer.api.service.software.Description;
import com.softlayer.api.service.software.component.Password;
import com.softlayer.api.service.virtual.Guest;
import com.softlayer.api.service.virtual.guest.Attribute;
import com.softlayer.api.service.virtual.guest.SupplementalCreateObjectOptions;
import com.softlayer.api.service.virtual.guest.block.Device;
import com.softlayer.api.service.virtual.guest.block.device.template.Group;
import com.softlayer.api.service.virtual.guest.network.Component;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.network.RawAddress;
import org.dasein.cloud.softlayer.SoftLayerCloud;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.uom.storage.Megabyte;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.storage.StorageUnit;
import org.dasein.util.uom.time.TimePeriod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.09
 */
public class SlVirtualMachineSupport extends AbstractVMSupport<SoftLayerCloud> {
    private transient volatile SlVirtualMachineCapabilities capabilities;
    private Account.Service accountService;
    private static final String VMPRODUCTS_CACHE = "vmproducts";
    private static final String LIST_VM_MASK = "mask[id, accountId, datacenter, location, regionalGroup, userData, createDate, powerState, fullyQualifiedDomainName, startCpus, maxMemory, operatingSystemReferenceCode, operatingSystem[softwareDescription, partitionTemplates, passwords], networkComponents[networkVlan], networkVlans[primaryRouter[datacenter]], status, provisionDate]";

    public SlVirtualMachineSupport(SoftLayerCloud provider) {
        super(provider);
    }
    
    private Account.Service getAccountService() throws InternalException {
        if( accountService == null ) {
            accountService = getProvider().getClient().createService(com.softlayer.api.service.Account.Service.class, getContext().getAccountNumber());
        }
        return accountService;
    }
    
    @Override
    public @Nonnull VirtualMachineCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new SlVirtualMachineCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        getAccountService().getVirtualGuests();
        return true;
    }
    
    @Override
    public @Nonnull Iterable<VirtualMachineProduct> listAllProducts() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.listAllProducts");
        try {
            Cache<VirtualMachineProduct> cache = Cache.getInstance(getProvider(), VMPRODUCTS_CACHE, VirtualMachineProduct.class, CacheLevel.REGION, new TimePeriod<>(7, TimePeriod.DAY));
            Iterable<VirtualMachineProduct> cachedProducts = cache.get(getContext());
            if( cachedProducts != null ) {
                return cachedProducts;
            }

            Description.Service softwareDescriptionSvc = Description.service(getProvider().getClient());
//            softwareDescriptionSvc.withNewMask().name().version().referenceCode();
            Configuration launchConfig = Guest.service(getProvider().getClient()).getCreateObjectOptions();
            long minRootVolumeSize = Long.MAX_VALUE;
            for( Option opt : launchConfig.getBlockDevices() ) {
                Device device = opt.getTemplate().getBlockDevices().get(0);
                if( "0".equals(device.getDevice()) ) {
                    if( device.getDiskImage().getCapacity() < minRootVolumeSize ) {
                        minRootVolumeSize = device.getDiskImage().getCapacity();
                    }
                }
            }
            List<VirtualMachineProduct> products = new ArrayList<>();
            for (Description description : softwareDescriptionSvc.getAllObjects()) {
                if( description.getOperatingSystem() != 1L || description.getReferenceCode() == null ) {
                    continue; // skip descriptions without refcode since we can't use them for launching
                }
                for( Option procOpt : launchConfig.getProcessors() ) {
                    for (Option memOpt : launchConfig.getMemory()) {
                        VirtualMachineProduct product = new VirtualMachineProduct();
                        product.setCpuCount(procOpt.getTemplate().getStartCpus().intValue());
                        product.setRamSize(new Storage<StorageUnit>(memOpt.getTemplate().getMaxMemory().intValue(), Storage.MEGABYTE));
                        product.setName(procOpt.getItemPrice().getItem().getDescription() + ", " + memOpt.getItemPrice().getItem().getDescription() + ", " + description.getName() + " " + description.getVersion() );
                        product.setDescription(product.getName());
                        product.setArchitectures(description.getReferenceCode().contains("32") ? Architecture.I32 : Architecture.I64);
                        product.getProviderMetadata().put("sw-id", String.valueOf(description.getId()));
                        product.getProviderMetadata().put("sw-refcode", description.getReferenceCode());
                        product.setProviderProductId(toProductId(procOpt.getTemplate().getStartCpus(), memOpt.getTemplate().getMaxMemory(), description.getReferenceCode()));
                        if( minRootVolumeSize < Long.MAX_VALUE ) {
                            product.setRootVolumeSize(new Storage<>(minRootVolumeSize, Storage.GIGABYTE));
                        }
                        else {
                            continue; // skip - without the root volume size it will be an invalid product :-(
                        }
                        products.add(product);
                    }
                }
            }
            cache.put(getContext(), products);
            return products;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nonnull Iterable<VirtualMachineProduct> listProducts(@Nonnull String providerImageId, @Nonnull VirtualMachineProductFilterOptions options) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.listProducts");
        try {
            MachineImage image = getProvider().getComputeServices().getImageSupport().getImage(providerImageId);
            if( image == null ) {
                throw new InternalException("Invalid image " + providerImageId);
            }
            String swId = image.getProviderMetadata().get("sw-id");
            Description.Service swDescSvc = getProvider().getClient().createService(Description.Service.class, swId);
            Description desc = swDescSvc.getObject();
            List<Description> refCodes = swDescSvc.getCompatibleSoftwareDescriptions();
            refCodes.add(desc); // add the image's software
            
            List<VirtualMachineProduct> products = new ArrayList<>();
            for( VirtualMachineProduct product : listAllProducts() ) {
                for( Description description : refCodes ) {
                    if( description.getReferenceCode().equals(product.getProviderMetadata().get("sw-refcode")) ) {
                        products.add(product);
                    }
                }
            }
            return products;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nullable VirtualMachineProduct getProduct(@Nonnull String productId) throws InternalException, CloudException {
        for( VirtualMachineProduct product : listAllProducts() ) {
            if( product.getProviderProductId().equals(productId) ) {
                return product;
            }
        }
        return null;
    }

    @Override
    public @Nonnull Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        List<VirtualMachine> machines = new ArrayList<>();
        Account.Service accountService = getAccountService();
        accountService.setMask(LIST_VM_MASK);        
        for( Guest guest : accountService.getVirtualGuests() ) {
            VirtualMachine vm = toVirtualMachine(guest);
            if( vm != null ) {
                machines.add(vm);
            }
        }
        return machines;
    }

    private String toProductId(long startCpus, long maxMemory, String referenceCode) {
        return startCpus + ":" + maxMemory + ":" + referenceCode;
    }
    
    protected @Nullable VirtualMachine toVirtualMachine(@Nullable Guest guest) {
        if( guest == null ) {
            return null;
        }
        VirtualMachine vm = new VirtualMachine();
        vm.setProviderVirtualMachineId(String.valueOf(guest.getId()));
        vm.setCreationTimestamp(guest.getCreateDate().getTimeInMillis());
        vm.setName(guest.getFullyQualifiedDomainName());
        if( guest.isNotesSpecified() ) {
            vm.setDescription(guest.getNotes());
        } else {
            vm.setDescription(vm.getName());
        }
        if( guest.getDatacenter() != null ) {
            vm.setProviderRegionId(guest.getDatacenter().getName());
            vm.setProviderDataCenterId(vm.getProviderRegionId());
        }
        if( guest.getAccountId() != null ) {
            vm.setProviderOwnerId(String.valueOf(guest.getAccountId()));
        }
        Description osDescription = guest.getOperatingSystem().getSoftwareDescription();
        vm.setProductId(toProductId(guest.getStartCpus(), guest.getMaxMemory(), osDescription.getReferenceCode()));
        vm.setPlatform(Platform.guess(osDescription.getLongDescription()));
        vm.setArchitecture(osDescription.getLongDescription().contains("64") ? Architecture.I64 : Architecture.I32);
        String requiredUser = osDescription.getRequiredUser();
        for( Password password : guest.getOperatingSystem().getPasswords() ) {
            if( requiredUser.equals(password.getUsername()) ) {
                vm.setRootUser(password.getUsername());
                vm.setRootPassword(password.getPassword());
            }
        }
        List<Component> networkComponents = guest.getNetworkComponents();
        List<RawAddress> privateIps = new ArrayList<>();
        List<RawAddress> publicIps = new ArrayList<>();
        for( Component component : networkComponents ) {
            RawAddress address = new RawAddress(component.getPrimaryIpAddress());
            if( address.getIpAddress().startsWith("10.") ) {
                privateIps.add(address);
            }
            else {
                publicIps.add(address);
            }
        }
        if( guest.getProvisionDate() == null ) {
            vm.setCurrentState(VmState.PENDING);
        } else {
            switch( guest.getPowerState().getKeyName() ) {
                case "RUNNING":
                    vm.setCurrentState(VmState.RUNNING);
                    break;
                case "HALTED":
                    vm.setCurrentState(VmState.STOPPED);
                    break;
                case "PAUSED":
                    vm.setCurrentState(VmState.PAUSED);
                    break;
            }
        }
        
        vm.setPrivateAddresses(privateIps.toArray(new RawAddress[privateIps.size()]));
        vm.setPublicAddresses(publicIps.toArray(new RawAddress[publicIps.size()]));
        for( Password password : guest.getOperatingSystem().getPasswords() ) {
            String username = password.getUsername();
            if( "root".equals(username) || "Administrator".equals(username)) {
                vm.setRootUser(username);
                vm.setRootPassword(password.getPassword());
                break;
            }
        }
        return vm;
    }

    @Override
    public @Nonnull VirtualMachine launch(@Nonnull VMLaunchOptions vmLaunchOptions) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "vm.launch");
        try {
            Guest.Service service = Guest.service(getProvider().getClient());
            Guest guest = new Guest();
            Group.Service groupSvc = Group.service(getProvider().getClient(), Long.parseLong(vmLaunchOptions.getMachineImageId()));
            guest.setBlockDeviceTemplateGroup(groupSvc.getObject());
            guest.setHostname(vmLaunchOptions.getHostName());
            guest.setNotes(vmLaunchOptions.getDescription());
            
            if( vmLaunchOptions.getDnsDomain() != null ) {
                guest.setDomain(vmLaunchOptions.getDnsDomain());
            }
            else {
                // get a fresh account service without any masks
                Account.Service account = Account.service(getProvider().getClient());
                List<Domain> domains = account.getDomains();
                if( domains.isEmpty() ) {
                    guest.setDomain("example.com");
                }
                else {
                    guest.setDomain(domains.get(0).getName());
                }
            }
            
            VirtualMachineProduct product = getProduct(vmLaunchOptions.getStandardProductId());
            guest.setStartCpus((long) product.getCpuCount());
            guest.setMaxMemory(product.getRamSize().longValue());
            guest.setHourlyBillingFlag(true);
            guest.setLocalDiskFlag(true);
//            guest.setOperatingSystemReferenceCode(product.getProviderMetadata().get("sw-refcode"));
            guest.setDatacenter(new Location());
            guest.getDatacenter().setName(getContext().getRegionId());

            guest = service.createObject(guest);

            service = guest.asService(getProvider().getClient());
            service.setMask(LIST_VM_MASK);
            do {
                TimeUnit.SECONDS.sleep(30);
                guest = service.getObject();
            } while( guest.getDatacenter() == null );
            if( vmLaunchOptions.getUserData() != null ) {
                service.setUserMetadata(Arrays.asList(vmLaunchOptions.getUserData()));
            }
            VirtualMachine vm = toVirtualMachine(guest);
            return vm;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Nullable
    @Override
    public VirtualMachine getVirtualMachine(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.getVirtualMachine");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(vmId));
            guestSvc.setMask(LIST_VM_MASK);
            return toVirtualMachine(guestSvc.getObject());
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( NumberFormatException | ApiException.NotFound e ) {
            return null; // invalid input or not found
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nullable String getPassword(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.getPassword");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if( vm != null ) {
                return vm.getRootPassword();
            }
            return null;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nullable String getUserData(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.getUserData");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(vmId));
            guestSvc.setMask(LIST_VM_MASK);
            Guest guest = guestSvc.getObject();
            for( Attribute attribute : guest.getUserData() ) {
                if( "USER_DATA".equals(attribute.getType().getKeyname()) ) {
                    return attribute.getValue();
                }
            }
            return null;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( NumberFormatException | ApiException.NotFound e ) {
            return null; // invalid input or not found
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void terminate(@Nonnull String providerVirtualMachineId, @Nullable String explanation) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.terminate");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            guestSvc.deleteObject();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }         
    }

    @Override
    public void unpause(@Nonnull String providerVirtualMachineId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "vm.unpause");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            while( null != guestSvc.getActiveTransaction() ) {
                TimeUnit.SECONDS.sleep(30);
            }
            guestSvc.resume();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void start(@Nonnull String providerVirtualMachineId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.start");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            guestSvc.powerOn();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void stop(@Nonnull String providerVirtualMachineId, boolean force) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.stop");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            guestSvc.powerOff();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void reboot(@Nonnull String providerVirtualMachineId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "vm.start");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            guestSvc.rebootDefault();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }

    }

    @Override
    public void pause(@Nonnull String providerVirtualMachineId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "vm.pause");
        try {
            Guest.Service guestSvc = Guest.service(getProvider().getClient(), Long.parseLong(providerVirtualMachineId));
            while( null != guestSvc.getActiveTransaction() ) {
                TimeUnit.SECONDS.sleep(30);
            }
            guestSvc.pause();
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( Exception e ) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
}
