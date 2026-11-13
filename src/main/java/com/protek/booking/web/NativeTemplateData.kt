package com.protek.booking.web

import io.quarkus.qute.TemplateData

/**
 * Every type a template reads, listed so that Qute generates a value resolver for ALL of its
 * public members. Without this the native binary answered 500 on `/movies`, `/cinemas` and every
 * seat map (2026-10-09). The resolvers Qute generated on its own answered only the
 * members that appear as `{msg:…}` arguments: `Film` got `title`, `durationMinutes` and
 * `releaseDate`, and not `posterUrl` or `slug`. A JVM run never notices, because it falls back to
 * reflection. The Java tree from before the Kotlin port (on Quarkus 3.38.3) fails the same
 * way, so this is not a Kotlin effect.
 *
 * The list is every `com.protek.booking` type reachable from a `@CheckedTemplate` method's
 * parameters through public members, plus the `@Named` beans the templates read as `{inject:…}`.
 * **A new type handed to a template belongs here**, or it works under `./mvnw test` and fails only
 * in the image. One `@TemplateData(target = …)` list, rather than an annotation on each class,
 * keeps it in one place.
 */
@TemplateData(target = com.protek.booking.domain.AppUser::class)
@TemplateData(target = com.protek.booking.domain.BookingDraft::class)
@TemplateData(target = com.protek.booking.domain.Film::class)
@TemplateData(target = com.protek.booking.domain.Payment::class)
@TemplateData(target = com.protek.booking.domain.PublicHoliday::class)
@TemplateData(target = com.protek.booking.domain.Reservation::class)
@TemplateData(target = com.protek.booking.domain.ReservationGroup::class)
@TemplateData(target = com.protek.booking.domain.ResourcePool::class)
@TemplateData(target = com.protek.booking.domain.ResourcePoolSession::class)
@TemplateData(target = com.protek.booking.domain.ResourceUnit::class)
@TemplateData(target = com.protek.booking.domain.SeatMapEntry::class)
@TemplateData(target = com.protek.booking.domain.Site::class)
@TemplateData(target = com.protek.booking.domain.Tenant::class)
@TemplateData(target = com.protek.booking.i18n.LanguagePreference::class)
@TemplateData(target = com.protek.booking.i18n.StatusLabels::class)
@TemplateData(target = com.protek.booking.repository.ReservationGroupRepository.HistoryEntry::class)
@TemplateData(target = com.protek.booking.repository.ResourcePoolSessionRepository.FilmShowing::class)
@TemplateData(target = com.protek.booking.repository.SiteRepository.CityCount::class)
@TemplateData(target = com.protek.booking.security.CurrentActor::class)
@TemplateData(target = com.protek.booking.security.StaffNav::class)
@TemplateData(target = com.protek.booking.seo.PageMeta::class)
@TemplateData(target = com.protek.booking.seo.PageMeta.Alternate::class)
@TemplateData(target = com.protek.booking.service.AdmissionService.Outcome::class)
@TemplateData(target = com.protek.booking.service.AdmissionService.ScanResult::class)
@TemplateData(target = com.protek.booking.service.AgeClassification.Advisory::class)
@TemplateData(target = com.protek.booking.service.AgeClassification.Gate::class)
@TemplateData(target = com.protek.booking.service.BookingDraftService.BookingPage::class)
@TemplateData(target = com.protek.booking.service.BookingDraftService.ShowDate::class)
@TemplateData(target = com.protek.booking.service.BookingDraftService.ShowTime::class)
@TemplateData(target = com.protek.booking.service.BookingService.Orders::class)
@TemplateData(target = com.protek.booking.service.CalendarPricing.SeatPrice::class)
@TemplateData(target = com.protek.booking.service.CatalogService.RelayoutPreview::class)
@TemplateData(target = com.protek.booking.service.CatalogService.SeatMapPage::class)
@TemplateData(target = com.protek.booking.service.CustomerProfileService.Profile::class)
@TemplateData(target = com.protek.booking.service.PurchaseCap::class)
@TemplateData(target = com.protek.booking.service.RatePlan::class)
@TemplateData(target = com.protek.booking.service.RatePlan.Band::class)
@TemplateData(target = com.protek.booking.service.SeatMapDiff::class)
@TemplateData(target = com.protek.booking.service.SeatMapDiff.Repositioning::class)
@TemplateData(target = com.protek.booking.service.SeatMapPlan.Seat::class)
@TemplateData(target = com.protek.booking.service.SeatMapPreview::class)
@TemplateData(target = com.protek.booking.service.ShowtimeViability::class)
@TemplateData(target = com.protek.booking.web.ComplaintContact::class)
@TemplateData(target = com.protek.booking.web.SignInLink::class)
@TemplateData(target = com.protek.booking.web.customer.ChatResource.ChatTurn::class)
@TemplateData(target = com.protek.booking.web.customer.CityPreference::class)
@TemplateData(target = com.protek.booking.web.dto.ReservationGroupDetail::class)
@TemplateData(target = com.protek.booking.web.dto.ReservationGroupDetail.ReservationLine::class)
@TemplateData(target = com.protek.booking.web.dto.SeatRow::class)
@Suppress("unused")
private class NativeTemplateData
