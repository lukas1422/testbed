/* Copyright (C) 2019 Interactive Brokers LLC. All rights reserved. This code is subject to the terms
 * and conditions of the IB API Non-Commercial License or the IB API Commercial License, as applicable. */

package client;

public class HistoricalTickBidAsk {
    private long m_time;
    private TickAttribBidAsk m_tickAttribBidAsk;
    private double m_priceBid;
    private double m_priceAsk;
    private Decimal m_sizeBid;
    private Decimal m_sizeAsk;

    public HistoricalTickBidAsk(long time, TickAttribBidAsk tickAttribBidAsk, double priceBid, double priceAsk, Decimal sizeBid, Decimal sizeAsk) {
        m_time = time;
        m_tickAttribBidAsk = tickAttribBidAsk;
        m_priceBid = priceBid;
        m_priceAsk = priceAsk;
        m_sizeBid = sizeBid;
        m_sizeAsk = sizeAsk;
    }

    public long time() {
        return m_time;
    }

    public TickAttribBidAsk tickAttribBidAsk() {
        return m_tickAttribBidAsk;
    }

    public double priceBid() {
        return m_priceBid;
    }

    public double priceAsk() {
        return m_priceAsk;
    }

    public Decimal sizeBid() {
        return m_sizeBid;
    }

    public Decimal sizeAsk() {
        return m_sizeAsk;
    }

}