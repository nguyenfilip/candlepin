/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.common.jackson;

import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.ser.PropertyWriter;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;

@RunWith(MockitoJUnitRunner.class)
public class DynamicPropertyFilterTest {

    @Mock
    private DynamicFilterData dynamicFilterData;

    @Mock
    private PropertyWriter writer;

    @Mock
    private JsonGenerator jsonGenerator;

    @Mock
    private JsonStreamContext context;

    @Before
    public void prepareMocks() {
        when(jsonGenerator.getOutputContext()).thenReturn(context);
        when(context.getParent()).thenReturn(context).thenReturn(null);
        when(context.getCurrentName()).thenReturn("CONTEXT_NAME_1");
        when(writer.getName()).thenReturn("WRITER_NAME");
    }

    @Test
    public void emptyFilterData() {
        ResteasyProviderFactory.pushContext(DynamicFilterData.class, null);
        DynamicPropertyFilter propertyFilter = new DynamicPropertyFilter();
        Assert.assertTrue(propertyFilter.isSerializable(null, jsonGenerator, null, writer));
        verifyZeroInteractions(jsonGenerator, writer);
    }

    @Test
    public void nonEmptyIsSerializable() {
        //Implicitly return true
        when(dynamicFilterData.isAttributeExcluded(anyList()))
            .thenReturn(true);
        when(dynamicFilterData.isAttributeExcluded(Arrays.asList("CONTEXT_NAME_1", "WRITER_NAME")))
            .thenReturn(false);

        ResteasyProviderFactory.pushContext(DynamicFilterData.class, dynamicFilterData);
        DynamicPropertyFilter propertyFilter = new DynamicPropertyFilter();
        Assert.assertTrue(propertyFilter.isSerializable(null, jsonGenerator, null, writer));
        verify(jsonGenerator).getOutputContext();
        verify(context, times(2)).getParent();
        verify(context).getCurrentName();
    }

    @Test
    public void nonEmptyIsNotSerializable() {
        when(dynamicFilterData.isAttributeExcluded(Arrays.asList("CONTEXT_NAME_1", "WRITER_NAME")))
            .thenReturn(true);

        ResteasyProviderFactory.pushContext(DynamicFilterData.class, dynamicFilterData);
        DynamicPropertyFilter propertyFilter = new DynamicPropertyFilter();
        Assert.assertFalse(propertyFilter.isSerializable(null, jsonGenerator, null, writer));
        verify(jsonGenerator).getOutputContext();
        verify(context, times(2)).getParent();
        verify(context).getCurrentName();
    }

}
