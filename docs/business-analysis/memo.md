# Should a small cinema-booking platform continue? A worked example

A one-page continue-or-stop memo for a one-person software business that sells this platform to a
regional cinema chain: a paid pilot at one branch first, then a flat monthly fee per screen. It
reads `model.xlsx` beside this file, and `B1.md` is the brief that produced both.

**The numbers are randomized examples.** They were drawn within reasonable ranges from a real
analysis, and they describe no real business, person or cinema. Change the blue cells in
`model.xlsx`'s `Inputs` sheet to your own numbers, and every sheet recalculates. The law section
does not depend on the numbers.

Month 1 is January 2027.

## Recommendation for this example: continue with conditions

Continue **beside paid work**, and only as far as the next gate: the pilot agreement signed and
its first instalment received by month 4. Do not give up the paid work for it.

Cash is not the risk. No case runs out of cash, and the largest loss is Rp7,53 juta, about half of
the Rp14 juta set aside. The risk is the owner's time.

| 24 months | Worst | Base | Best |
|---|---|---|---|
| Largest cash loss from the Rp14 juta | Rp7,53 juta | Rp6,19 juta | Rp6,19 juta |
| Month the cash runs out | never | never | never |
| First paying branch | none | month 12 (Dec 2027) | month 9 (Sep 2027) |
| Paying branches at month 24 | 0 | 3 | 11 |
| Cash result | −Rp7,5 juta | +Rp40,5 juta | +Rp173,4 juta |
| Result if the business costs the whole job (Rp6.250.000 a month) | −Rp32,5 juta | **−Rp114,0 juta** | +Rp5,5 juta |
| Result if it costs only its own 55 h a month (Rp36.127/h) | −Rp15,5 juta | −Rp11,7 juta | +Rp107,8 juta |

- **Per branch, per month:** revenue Rp2.200.000 (4 screens × Rp550.000), cash margin
  Rp2.189.000 (99,5 %), and Rp1.972.237 after 6 h of support.
- **Acquiring the first branch** costs Rp8,0 juta in cash: the visit, the head-office demo, the
  demo kit, the lawyer, the accountant, training and pilot visits. The Rp18,5 juta pilot fee
  covers it with Rp10,5 juta left.
- **Break-even:** 1 branch covers the fixed cash costs (Rp20.000 a month, or Rp670.000 with a
  VPS). Counting only the 55 h, it takes **2 branches**. If the business costs the whole job, it
  takes **4**, which the base case does not reach in 24 months. On that whole-job basis,
  Rp250.000 a screen needs 9 branches, Rp350.000 needs 6, Rp750.000 needs 3 and Rp1.000.000
  needs 2.
- **The base case turns positive late.** Over 24 months it loses Rp11,7 juta even counting only
  the 55 h, but by month 24 it earns Rp2,9 juta a month after that time. The first two years buy
  the third.
- **Stop rules:**
  - Rule 1 (cash limit) holds in every case.
  - Rule 2 (a paying branch by month 12) is met by the base case with no month to spare, and by
    the best case.
  - Rule 3 (signed by month 4) ends the worst case in month 4.
  - Rule 4 (hosting) triggers only in the worst case, which has already stopped. In the others the
    first instalment pays for the VPS.
  - Rule 5 (hours) is never triggered. But the best case peaks at 121 h a month, which no side
    project can carry.

### Conditions

1. **Spend in order of cost.** The first branch visit (Rp200.000) comes before the demo kit and the
   head-office trips. Ask there for the counter's monthly ticket count and the month the branch
   would start paying after the pilot.
2. **Settle rule 2 in the rollout terms.** The base case reaches its first paying month exactly on
   the stop date, so a one-month slip stops it. Write the start of the monthly fee into the
   rollout agreement.
3. **At the rollout decision, require a written path to 2 paying branches** while the business
   runs beside the job, or to 4 if it would replace the job, or else a higher price.
4. **Budget the support hours before the best case arrives.** At 3 branches, 6 h each already
   pushes the month past the 70 h cap (55 + 18 = 73 h). Either price support into the fee or plan to hire.
5. **Keep the unearned part of the pilot fee unspent.** A pilot agreement that ends early usually
   refunds the fee pro rata, here about Rp206.000 a day.

### What would reverse it

- **The hours given up** (`Inputs` B61). If continuing really costs the whole job, only the best
  case pays within 24 months, and the answer is stop unless the chain commits to 4 or more
  branches.
- **The price.** At Rp250.000 a screen, 2 branches become 3 beside the job, and 4 become 9 if it
  replaces the job.
- **The chain wants the pilot free** (rule 3), or free hosting disappears while there is no revenue
  (rule 4).
- The pilot fee, the cash balance and the VPS do not reverse it at any value near these. Taking
  away the VPS improves the base case's cash result by Rp14,3 juta, to Rp54,8 juta.

One number to take to the first visit: at this volume (14.040 tickets a branch a month),
Rp550.000 a screen is Rp157 a ticket. A Rp1.250-a-ticket fallback would earn Rp17,6 juta a branch
a month once the counter sells through the system, eight times the flat price, but only Rp2,3 juta
while just a block of seats is online.

## Stopping under Indonesian law (orientation, not legal advice)

Each point was checked against statute text found by web search on 2026-11-11. The texts were
read on `pasal.id` and `api-law.crpg.info`, which show the original wording and do not map later
amendments (UU 6/2023, and a 2026 amendment `pasal.id` lists as UU 5/2026). A lawyer should
confirm the current wording before any of this is acted on.

**Before a PT exists, you act as an individual.**
- An agreement is then signed as *perorangan*, with KTP and NPWP. Give it an assignment clause
  so it can move to the PT later.
- An individual has no separate estate. KUHPerdata Pasal 1131 makes all of a debtor's property,
  movable and immovable, present and future, answer for the debtor's obligations (secondary
  sources; their wording differs slightly).
- Stopping as an individual needs no dissolution. It means ending each agreement under its own
  termination clause. A pilot agreement should carry one with written notice (14 days is common),
  the fee settled pro rata within a fixed time, no court ruling needed, and named clauses
  (confidentiality, liability) surviving.

**Voluntary dissolution of a PT Perorangan with no unpaid debts.**
- PP 8/2021 Pasal 13(1): dissolution is decided by the shareholder, with the force of a RUPS, set
  out in a *Pernyataan Pembubaran* and notified electronically to the Menteri. Pasal 13(2)(a) names
  that decision as a ground.
- Pasal 13(3) and (4): the shareholder appoints a likuidator. If none is appointed, the Direksi is
  the likuidator. In a PT Perorangan that is the founder.
- UU 40/2007 Pasal 142(2): dissolution must be followed by liquidation, and the Perseroan may act
  only to wind up.
- UU 40/2007 Pasal 147(1) and (3): within 30 days the likuidator announces the dissolution to
  creditors in a newspaper and the Berita Negara and notifies the Menteri. Creditors have 60 days
  to claim.
- UU 40/2007 Pasal 149(1): record assets and debts, announce the distribution plan, pay creditors,
  then pay the remainder to the shareholder.
- Procedure: Permenkum 49/2025, in force 2025-12-17, replaced Permenkumham 21/2021. The statement
  is filed through SABH and the legal-entity status ends when it is registered (secondary sources,
  which cite its Pasal 29). The NPWP and the NIB are closed separately.
- Not established: how far the Pasal 147 announcements apply to a PT Perorangan in practice, and
  what Permenkum 14/2026 changed about them.

**When there are unpaid debts.**
- UU 40/2007 Pasal 149(2): a likuidator who estimates that debts exceed assets must file for the
  Perseroan's bankruptcy, unless every known creditor agrees to settle outside bankruptcy.
- UU 37/2004 Pasal 2(1): a debtor with two or more creditors who has not paid in full at least one
  debt that is due and collectable is declared *pailit* by the court, on its own petition or a
  creditor's.
- UU 37/2004 Pasal 222(1) and (2): PKPU is filed by a debtor with more than one creditor, or by a
  creditor. A debtor who cannot, or expects not to be able to, keep paying due debts may ask for
  it in order to offer a composition plan.
- Both need at least two creditors. With one creditor it is an ordinary civil claim.
- Pasal 1 angka 3 defines Debitor as an *orang*, so both also apply to an individual.
- UU 40/2007 Pasal 104(1): the Direksi may file the Perseroan's own bankruptcy only with RUPS
  approval.

**Personal liability once the PT exists.**
- Shareholder: UU 40/2007 Pasal 3(1) limits liability to the shares. Pasal 3(2) removes the limit
  when the legal-entity requirements are not met, when the shareholder uses the Perseroan in bad
  faith for personal interest, takes part in its unlawful act, or unlawfully uses its assets so
  that they no longer cover its debts. Pasal 153J(1) and (2) repeat the same rule and the same
  four exceptions for the PT Perorangan.
- Director: Pasal 104(2) makes each director jointly liable for what the estate cannot pay when
  the bankruptcy came from the Direksi's fault or negligence. Ayat (4) frees a director who proves
  no fault, good faith and care, no conflict of interest and steps taken to prevent it.
- In practice: a separate bank account, and no company money moved to yourself before debts are
  paid.

**Customers' payments and tenants in a wind-down.**
- In this setup the ticket money settles into the tenant's own payment-gateway account and never
  passes through the operator. The refund of a ticket for a showing that will not run is the
  tenant's debt as seller. UU 8/1999 Pasal 19(1) to (3): the *pelaku usaha* compensates, a refund
  counts, and it is due within 7 days of the transaction.
- The operator's part is operational. Do not switch the system off while paid tickets for future
  showings exist. Set a last sale date, let those showings run or let the tenant refund them, and
  hand the tenant its booking and payment records.
- To the tenant you owe the notice and the pro rata refund of the agreement's termination clause.
  A monthly agreement invoiced in advance should carry the same clause.
- Personal data: UU 27/2022 Pasal 48(1) to (4) requires a controller that is a *badan hukum* and
  dissolves to notify data subjects before and after, and to tell them whether the data is stored,
  transferred, deleted or destroyed. Whether the operator or the tenant is the controller is the
  open question in [`../pse-oss-registration.md`](../pse-oss-registration.md) §2.

## What this memo rests on

- **The model:** 2.666 formulas, no errors after a LibreOffice recalculation, so the workbook
  stores its computed values and still recalculates when an input changes. The base case's cash
  result was checked by hand: Rp64,7 juta in, Rp24,18 juta out.
- **Placement guesses, yellow in `Inputs`:** the months of the head-office trips, the accountant
  and Google Play; the onboarding cost of each later branch; branches added in a straight line
  between the four case points, rounded down.
- **Not checked:** the tax figures (PPh Final 0,5 %, the Rp500 juta exemption for an individual,
  the UMK PNBP), the PSE filing fee, and how a PSE registration is closed.

## Sources

- UU 37/2004: <https://pasal.id/en/peraturan/uu/uu-no-37-tahun-2004>,
  <https://pasal.id/peraturan/uu/uu-no-37-tahun-2004/pasal-222>
- UU 40/2007 Pasal 3 and 104: <https://api-law.crpg.info/peraturan/uu/40/2007/pasal/3>,
  <https://api-law.crpg.info/peraturan/uu/40/2007/pasal/104>
- UU 40/2007 Pasal 142, 147, 149: <https://pasal.id/peraturan/uu/uu-no-40-tahun-2007/pasal-142>,
  <https://pasal.id/peraturan/uu/uu-no-40-tahun-2007/pasal-147>,
  <https://pasal.id/peraturan/uu/uu-no-40-tahun-2007/pasal-149>
- Pasal 153J: <https://pasal.id/peraturan/uu/uu-no-11-tahun-2020/pasal-153j>
- PP 8/2021 Pasal 13: <https://api-law.crpg.info/peraturan/pp/8/2021/pasal/13>,
  <https://peraturan.bpk.go.id/Details/161838/pp-no-8-tahun-2021>
- Permenkum 49/2025 and the filing steps: <https://justisio.com/blog/tinjau-permenkum-49-2025>,
  <https://ukmindonesia.id/baca-deskripsi-posts/pembubaran-pt-perorangan-inilah-syarat-ketentuan-dan-langkahnya>
- UU 8/1999 Pasal 19: <https://api-law.crpg.info/peraturan/uu/8/1999/pasal/19>
- UU 27/2022 Pasal 48: <https://pasal.id/peraturan/uu/uu-no-27-tahun-2022/pasal-48>
- KUHPerdata Pasal 1131:
  <https://www.hukumonline.com/klinik/a/jenis-dan-contoh-jaminan-kebendaan-lt518f8c34e5c67/>
