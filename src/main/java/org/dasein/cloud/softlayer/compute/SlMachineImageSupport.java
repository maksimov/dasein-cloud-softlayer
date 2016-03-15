package org.dasein.cloud.softlayer.compute;

import com.softlayer.api.ApiException;
import com.softlayer.api.service.Account;
import com.softlayer.api.service.Location;
import com.softlayer.api.service.container.disk.image.capture.template.*;
import com.softlayer.api.service.container.disk.image.capture.template.Volume;
import com.softlayer.api.service.provisioning.version1.Transaction;
import com.softlayer.api.service.provisioning.version1.transaction.Status;
import com.softlayer.api.service.virtual.Guest;
import com.softlayer.api.service.virtual.disk.Image;
import com.softlayer.api.service.virtual.disk.image.Software;
import com.softlayer.api.service.virtual.guest.block.Device;
import com.softlayer.api.service.virtual.guest.block.device.Template;
import com.softlayer.api.service.virtual.guest.block.device.template.Group;
import com.softlayer.api.service.virtual.guest.block.device.template.group.Accounts;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.softlayer.SoftLayerCloud;
import org.dasein.cloud.util.APITrace;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.09
 */
public class SlMachineImageSupport extends AbstractImageSupport<SoftLayerCloud> {
    
    private final static String IMAGE_MASK1 = "mask[id, createDate, accountId, statusId, datacenters, publicFlag, name, summary, note, imageTypeKeyName, blockDevices[device, diskImage], blockDevicesDiskSpaceTotal]";

    private final static Group.Mask IMAGE_MASK = new Group.Mask();
    static {
        IMAGE_MASK.id().createDate().accountId().statusId().publicFlag().name().summary().note().imageTypeKeyName().blockDevicesDiskSpaceTotal().tagReferences();
        IMAGE_MASK.blockDeviceCount().blockDevices().id().device().diskImageId().diskImage().softwareReferences().softwareDescription();
        IMAGE_MASK.parent();
        IMAGE_MASK.transactionId().children().blockDevicesDiskSpaceTotal().blockDevices().id().device().diskImageId().diskImage().softwareReferences().softwareDescription();
        IMAGE_MASK.datacenters();
        IMAGE_MASK.datacenter();
    }
    protected SlMachineImageSupport(SoftLayerCloud provider) {
        super(provider);
    }

    private transient volatile SlMachineImageCapabilities capabilities;

    @Override
    public ImageCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new SlMachineImageCapabilities(getProvider());
        }
        return capabilities;
    }
    
    @Override
    public @Nullable MachineImage getImage(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.getImage");
                
        try {
//            for (MachineImage image : listImages(ImageFilterOptions.getInstance())) {
//                if( providerImageId.equals(image.getProviderMachineImageId())) {
//                    return image;
//                }
//            }
//            return null;
//        }
            Group.Service groupSvc = getProvider().getClient().createService(Group.Service.class, providerImageId);
            groupSvc.setMask(IMAGE_MASK);
            return toImage(groupSvc.getObject(), getContext().getRegionId());
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch( ApiException.NotFound notFound ) {
            // surprisingly this exception isn't really thrown here, but leave it in case they fix it
            return null;
        }
        catch( ApiException.Internal internal ) {
            if( "SoftLayer_Exception_ObjectNotFound".equalsIgnoreCase(internal.code) ) {
                return null;
            }
            throw new GeneralCloudException(internal);
        }
        catch (Exception e) {
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true; // TODO: be smarter here
    }

    private @Nullable String getSoftwareDescription(@Nonnull Group group) {
        String description = null;
        if( group.getBlockDevices() != null && !group.getBlockDevices().isEmpty()) {
            Template tpl = group.getBlockDevices().get(0);
            if( tpl.getDiskImage() != null && tpl.getDiskImage().getSoftwareReferences() != null && !tpl.getDiskImage().getSoftwareReferences().isEmpty()) {
                description =  tpl.getDiskImage().getSoftwareReferences().get(0).getSoftwareDescription().getReferenceCode();
            }
        }
        if( description == null && group.getChildren() != null && !group.getChildren().isEmpty()) {
            description = getSoftwareDescription(group.getChildren().get(0));
        }
        return description;
    }

    private @Nullable Long getSoftwareId(@Nonnull Group group) {
        Long softwareId = null;
        if( group.getBlockDevices() != null && !group.getBlockDevices().isEmpty()) {
            Template template = group.getBlockDevices().get(0);
            if( template.getDiskImage() != null && template.getDiskImage().getSoftwareReferences() != null && !template.getDiskImage().getSoftwareReferences().isEmpty() ) {
                softwareId = template.getDiskImage().getSoftwareReferences().get(0).getSoftwareDescription().getId();
            }
        }
        if( softwareId == null && group.getChildren() != null && !group.getChildren().isEmpty()) {
            softwareId = getSoftwareId(group.getChildren().get(0));
        }
        return softwareId;
    }
    
    protected @Nonnull MachineImage toImage(@Nonnull Group group, @Nonnull String regionId) throws InternalException {
        String typeName = group.getImageTypeKeyName(); // SYSTEM, DISK_CAPTURE...
        String description = (description = group.getNote()) == null ? "" : description;
        
        MachineImageState state = MachineImageState.PENDING;
        // TODO: improve status mapping for other states
        switch( group.getStatusId().intValue() ) {
            case 1:
                state = MachineImageState.ACTIVE;
                break;
            // TODO here;
        }
        // TODO: the type mapping is a guess 
        MachineImageType type = MachineImageType.VOLUME;
        Platform platform = Platform.guess(group.getName());
        if( platform.equals(Platform.UNKNOWN)) {
            platform = Platform.guess(description);
        }
        String softwareDescription = getSoftwareDescription(group);
        if( softwareDescription != null ) {
            platform = Platform.guess(softwareDescription);
        }
        
        MachineImage image = MachineImage.getInstance(String.valueOf(group.getAccountId()), regionId, String.valueOf(group.getId()), ImageClass.MACHINE, state, group.getName(), description, Architecture.I64, platform);
        if( group.isCreateDateSpecified() ) {
            image.createdAt(group.getCreateDate().getTimeInMillis());
        }
        BigDecimal diskSize = group.getBlockDevicesDiskSpaceTotal();
        if( diskSize.longValue() == 0 && group.getChildren() != null && !group.getChildren().isEmpty() ) {
            diskSize = group.getChildren().get(0).getBlockDevicesDiskSpaceTotal();
        }
        image.setMinimumDiskSizeGb(diskSize.longValue() / (1024 * 1024 * 1024) );
        Long softwareId = getSoftwareId(group);
        if( softwareId != null ) {
            image.getProviderMetadata().put("sw-id", softwareId.toString());
        }
        
        if( group.getPublicFlag() > 0 ) {
            image.sharedWithPublic();
        }
        image.withType(type);
        return image;        
    }
    
    @Override
    public @Nonnull Iterable<MachineImage> listImages(@Nullable ImageFilterOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.listImages");
        try {
            List<MachineImage> images = new ArrayList<>();
            String accountId = getContext().getEffectiveAccountNumber();
            Account.Service acct = getProvider().getClient().createService(com.softlayer.api.service.Account.Service.class, accountId);
            acct.setMask(IMAGE_MASK);
            for (Group group : acct.getBlockDeviceTemplateGroups()) {
                if( group.getParent() != null ) {
                    // skip the children images, as we only show top-level private images - as is the direct console
                    continue;
                }
                // add regions according to filter options
                List<String> regions = new ArrayList<>();
                for( Location region : group.getDatacenters() ) {
                    String regionId = region.getName();
                    if( options == null || options.getWithAllRegions() || regionId.equals(getContext().getRegionId()) ) {
                        regions.add(regionId);
                    }
                }
                if( regions.isEmpty() && group.getDatacenter() != null ) {
                    String regionId = group.getDatacenter().getName();
                    if( options == null || options.getWithAllRegions() || regionId.equals(getContext().getRegionId()) ) {
                        regions.add(regionId);
                    }
                }
                // denormalise image per regions if any
                for( String regionId : regions ) {
                    MachineImage image = toImage(group, regionId);
                    if( options == null || options.matches(image) ) {
                        images.add(image);
                    }
                }
            }
            return images;
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
    public void remove(@Nonnull String providerImageId, boolean checkState) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.remove");
        try {
            // TODO: check this after vm#launch and capture are implemented 
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            Transaction tx = groupSvc.deleteObject();
            Status status = tx.getTransactionStatus();
            System.out.println(status.getFriendlyName());
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void addImageShare(@Nonnull String providerImageId, @Nonnull String accountNumber) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.addImageShare");
        try {
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            groupSvc.permitSharingAccess(Long.parseLong(accountNumber));
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void addPublicShare(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.addPublicShare");
        try {
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            Group group = groupSvc.getObject();
            group.setPublicFlag(1L);
            groupSvc.editObject(group);
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    protected MachineImage capture(@Nonnull ImageCreateOptions options, @Nullable AsynchronousTask<MachineImage> task) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.isImageSharedWithPublic");
        try {
            Guest.Service service = getProvider().getClient().createService(Guest.Service.class, options.getVirtualMachineId());
            Group blockDeviceTemplateGroup = service.getBlockDeviceTemplateGroup();
            Device bootableDevice = null;
            for (Template template : blockDeviceTemplateGroup.getBlockDevices()) {
                System.out.println(template.getDevice());
                for (Device device : template.getDiskImage().getBlockDevices()) {
                    if (device.getBootableFlag() == 1) {
                        bootableDevice = device;
                    }
                }
            }

            Transaction tx = service.createArchiveTransaction(options.getName(), Arrays.asList(bootableDevice), options.getDescription());
            while (true) {
                Status status = tx.getTransactionStatus();
                System.out.println(status.getName());
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
//            return null;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isImageSharedWithPublic(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.isImageSharedWithPublic");
        try {
            MachineImage image = getImage(providerImageId);
            if( image != null ) {
                return image.isPublic();
            }
            throw new ResourceNotFoundException("image", providerImageId);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removeAllImageShares(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.removeAllImageShares");
        try {
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            for( Accounts acct : groupSvc.getAccountReferences() ) {
                groupSvc.denySharingAccess(acct.getAccountId());
            }
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removeImageShare(@Nonnull String providerImageId, @Nonnull String accountNumber) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.removeImageShare");
        try {
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            groupSvc.denySharingAccess(Long.parseLong(accountNumber));
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removePublicShare(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.removePublicShare");
        try {
            Group.Service groupSvc = getProvider().getClient().createService(com.softlayer.api.service.virtual.guest.block.device.template.Group.Service.class, providerImageId);
            Group group = groupSvc.getObject();
            group.unsetPublicFlag();
            groupSvc.editObject(group);
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new InternalException(e);
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public @Nonnull Iterable<MachineImage> searchPublicImages(@Nonnull ImageFilterOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "mi.searchPublicImages");
        try {
            List<MachineImage> images = new ArrayList<>();
            Group.Service groupSvc = getProvider().getClient().createService(Group.Service.class, "");
            groupSvc.setMask(IMAGE_MASK);
            for (Group group : groupSvc.getPublicImages()) {
                List<String> regions = new ArrayList<>();
                // add regions according to filter options
                for( Location region : group.getDatacenters() ) {
                    String regionId = region.getName();
                    if( options.getWithAllRegions() || regionId.equals(getContext().getRegionId()) ) {
                        regions.add(regionId);
                    }
                }
                // denormalise image per regions if any
                for( String regionId : regions ) {
                    MachineImage image = toImage(group, regionId);
                    if( options.matches(image) ) {
                        images.add(image);
                    }

                }
            }
            return images;
        }
        catch( ApiException.Unauthorized e ) {
            throw new AuthenticationException(e.getMessage(), e);
        }
        finally {
            APITrace.end();
        }
    }
    
}
