package org.dasein.cloud.softlayer;

import org.dasein.cloud.Cloud;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.ResourceNotFoundException;
import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * @author Stas Maksimov (stas.maksimov@software.dell.com)
 * @since 0.9.9
 */
public class ExceptionsTest {
    
    public static void testMethod() throws CloudException {
        if( true ) {
            throw new ResourceNotFoundException("resource", "1");
        }
    }
    @Test
    public void exceptions() {
        try {
            testMethod();
        } catch( ResourceNotFoundException r ) {
            
        } catch( CloudException c ) {
            fail("Test failed");
        }
    }
}
