package com.chenweikeng.imf.nra.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImagineFunWindowIconHandlerTest {
  @Test
  void recognizesImagineFunHostsWithOptionalPorts() {
    assertTrue(ImagineFunWindowIconHandler.isImagineFunAddress("play.imaginefun.net"));
    assertTrue(ImagineFunWindowIconHandler.isImagineFunAddress("PLAY.IMAGINEFUN.NET:25565"));
    assertTrue(ImagineFunWindowIconHandler.isImagineFunAddress("imaginefun.net."));
  }

  @Test
  void rejectsLookalikeAndMissingHosts() {
    assertFalse(ImagineFunWindowIconHandler.isImagineFunAddress(null));
    assertFalse(ImagineFunWindowIconHandler.isImagineFunAddress(""));
    assertFalse(ImagineFunWindowIconHandler.isImagineFunAddress("imaginefun.net.example.com"));
    assertFalse(ImagineFunWindowIconHandler.isImagineFunAddress("notimaginefun.net"));
  }
}
