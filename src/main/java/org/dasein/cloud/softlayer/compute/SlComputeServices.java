package org.dasein.cloud.softlayer.compute;

import org.dasein.cloud.compute.MachineImageSupport;
import org.dasein.cloud.softlayer.SoftLayerCloud;
import org.dasein.cloud.compute.AbstractComputeServices;
import org.dasein.cloud.compute.VirtualMachineSupport;

import javax.annotation.Nullable;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 2015.09
 */
public class SlComputeServices extends AbstractComputeServices<SoftLayerCloud> {
    
    private SlVirtualMachineSupport virtualMachineSupport;
    private SlMachineImageSupport machineImageSupport;
    
    public SlComputeServices(SoftLayerCloud softLayerCloud) {
        super(softLayerCloud);
    }
    
    @Override
    public @Nullable VirtualMachineSupport getVirtualMachineSupport() {
        if( virtualMachineSupport == null ) {
            virtualMachineSupport = new SlVirtualMachineSupport(getProvider());
        }
        return virtualMachineSupport;
    }

    @Nullable
    @Override
    public MachineImageSupport getImageSupport() {
        if( machineImageSupport == null ) {
            machineImageSupport = new SlMachineImageSupport(getProvider());
        }
        return machineImageSupport;
    }
}
