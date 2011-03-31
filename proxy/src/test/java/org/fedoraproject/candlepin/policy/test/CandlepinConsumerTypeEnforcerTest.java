/**
 * Copyright (c) 2009 Red Hat, Inc.
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
package org.fedoraproject.candlepin.policy.test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.fedoraproject.candlepin.model.Consumer;
import org.fedoraproject.candlepin.model.Pool;
import org.fedoraproject.candlepin.policy.CandlepinConsumerTypeEnforcer;
import org.fedoraproject.candlepin.policy.js.ReadOnlyPool;
import org.fedoraproject.candlepin.policy.js.entitlement.PreEntHelper;
import org.fedoraproject.candlepin.policy.js.pool.PoolHelper;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * CandlepinConsumerTypeEnforcerTest
 */
public class CandlepinConsumerTypeEnforcerTest {

    private CandlepinConsumerTypeEnforcer ccte;
    
    @Before
    public void init() {
        ccte = new CandlepinConsumerTypeEnforcer();

    }

    @Test
    public void postEntitlement() {
        PoolHelper ph = mock(PoolHelper.class);
        assertEquals(ph, ccte.postEntitlement(null, ph, null));
    }
    
    @Test
    public void preEntitlement() {
        Consumer c = mock(Consumer.class);
        Pool p = mock(Pool.class);
        ReadOnlyPool roPool = mock(ReadOnlyPool.class);
        PreEntHelper peh = ccte.preEntitlement(c, p, 10);
        assertNotNull(peh);
        peh.checkQuantity(roPool);
        verify(roPool).entitlementsAvailable(eq(1));
    }
    
    @Test(expected = NullPointerException.class)
    public void bestPoolsNull() {
        ccte.selectBestPools(null, null, null);
    }
    
    @Test
    public void bestPoolEmpty() {
        assertEquals(null,
            ccte.selectBestPools(null, null, new ArrayList<Pool>()));
    }
    
    @Test
    public void bestPool() {
        List<Pool> pools = new ArrayList<Pool>();
        pools.add(mock(Pool.class));
        assertEquals(pools, ccte.selectBestPools(null, null, pools));
    }
    
}