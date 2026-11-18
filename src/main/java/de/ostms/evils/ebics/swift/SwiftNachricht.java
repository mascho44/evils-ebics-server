package de.ostms.evils.ebics.swift;

/** Eine erzeugte MT7xx-Demo-Nachricht - sowohl das interne Generator-Rueckgabeobjekt als auch die
 * API-Antwortform (siehe SwiftDemoController), da beide identisch sind. */
public record SwiftNachricht(String messageType, String referenz, String rohtext) {
}
