package com.variocube.vcmp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class BoxSnapshotHandler  {
    @VcmpListener
    public void handleMessage(VcmpHandlerTest.BoxSnapshot message) {
        log.info("BoxSnapshotHandler->handleMessage");
        //throw new RuntimeException("Blaaa");
    }

}
