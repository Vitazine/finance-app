package app.finance.core.money

// ISO 4217 List One, SIX, publication 2026-09-17.
// Extracted currency-code/minor-unit facts; source/hash in docs/testing/iso4217-minor-units.json.
internal object Iso4217MinorUnits {
    val byCode: Map<String, Int> = buildMap {
        for (code in codes(
            """
            BIF CLP DJF GNF ISK JPY KMF KRW PYG RWF UGX UYI VND VUV XAF XOF XPF
            """
        )) {
            put(code, 0)
        }
        for (code in codes(
            """
            AED AFN ALL AMD AOA ARS AUD AWG AZN BAM BBD BDT BMD BND BOB BOV BRL BSD BTN
            BWP BYN BZD CAD CDF CHE CHF CHW CNY COP COU CRC CUP CVE CZK DKK DOP DZD EGP
            ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GTQ GYD HKD HNL HTG HUF IDR ILS INR
            IRR JMD KES KGS KHR KPW KYD KZT LAK LBP LKR LRD LSL MAD MDL MGA MKD MMK MNT
            MOP MRU MUR MVR MWK MXN MXV MYR MZN NAD NGN NIO NOK NPR NZD PAB PEN PGK PHP
            PKR PLN QAR RON RSD RUB SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC
            SYP SZL THB TJS TMT TOP TRY TTD TWD TZS UAH USD USN UYU UZS VED VES WST XAD
            XCD XCG YER ZAR ZMW ZWG
            """
        )) {
            put(code, 2)
        }
        for (code in codes(
            """
            BHD IQD JOD KWD LYD OMR TND
            """
        )) {
            put(code, 3)
        }
        for (code in codes(
            """
            CLF UYW
            """
        )) {
            put(code, 4)
        }
    }

    val undefined: Set<String> = setOf(
        "XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XDR", "XPD", "XPT", "XSU", "XTS", "XUA", "XXX"
    )

    private fun codes(text: String): List<String> = text.trim().split(Regex("\\s+"))
}
