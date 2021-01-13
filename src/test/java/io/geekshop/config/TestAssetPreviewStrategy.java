/*
 * Copyright (c) 2020 GeekShop.
 * All rights reserved.
 */

package io.geekshop.config;

import io.geekshop.config.asset.AssetPreviewStrategy;

import java.io.UnsupportedEncodingException;
import java.util.Base64;

/**
 * A mock preview strategy which returns a new Buffer without doing any actual work.
 *
 * Created on Nov, 2020 by @author bobo
 */
public class TestAssetPreviewStrategy implements AssetPreviewStrategy {

    static final String TEST_IMAGE_BASE_64 = "iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyppVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuMy1jMDExIDY2LjE0NjcyOSwgMjAxMi8wNS8wMy0xMzo0MDowMyAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIiB4bWxuczp4bXBNTT0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL21tLyIgeG1sbnM6c3RSZWY9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZVJlZiMiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIEVsZW1lbnRzIDEyLjAgV2luZG93cyIgeG1wTU06SW5zdGFuY2VJRD0ieG1wLmlpZDowQzYyREUzNEM1QTcxMUU5OEJCNUU3Qzg2NjgyOTVFOSIgeG1wTU06RG9jdW1lbnRJRD0ieG1wLmRpZDowQzYyREUzNUM1QTcxMUU5OEJCNUU3Qzg2NjgyOTVFOSI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjBDNjJERTMyQzVBNzExRTk4QkI1RTdDODY2ODI5NUU5IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjBDNjJERTMzQzVBNzExRTk4QkI1RTdDODY2ODI5NUU5Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+1hstBAAAAvxJREFUeNrsmslu4zAQRGlZ8u7DXPL/HzZfMAcH8G7Ly7iEeUKboR0pkWIIQwKMrI2s6i52N4X0FovFb+fcL9fN9p7e/rzd+ryjBLLk9id33W154jreIoFIIBKIBCKB/5tA+qqJe73e3fn1en0NASYWoBAoe41nL5eLO5/PZU+SxI1Gow/vt0pAk202G3c8HgsAOtex3+8XnWsCLZCn06kATtd1SzDLsqLX9UT6VfCaSKB0tIDsM4DzrxcTp2kBWGPICBoj9HxjBACNRfM8LyaVpWez2R0RKxGsDCgrI43BfZFQw4ONEtAE+/3eHQ6HEiBNk8maobVhuyVliTKWCCBHjSejNOoBWUvuFmC5nmtYy7pdvwXk2eLmCCEIIic/AHybAIAULcbjsdvtdqXFACoA2+22OOq54XD4VNN6B2PotwyiMQkCVdZCUpcALhdIe11NEhMATSyCVmqh2O9LjTVTZw1UJsCgVr++PCCDhOw9/SZqPWq67xulEQlhFXWshEYtaEkGcvqNDAC/Wq3cZDIpn/NbyCiNrgENzGLDyr5WtT78TKx3lPR0LpkNBoOHUqrrgaROHgCszQF2Mr9sIP6v1+uCNJ7Q+2paJ/Y5O2bVZFYrE0tChFM/VFIucJ1IIsvrOuR0JIKp20iFVFsjQMJisQFKSQ5rhiTnaxoZ2gxNPgjJq7H9gB9ZZC1NKl2HFiSh9llpAhGerVuRVvaADY3olj6fz+9KA8DbyvNRQsObtkZqrRoVWNYB5TSkCLPURv6Cxtq2yLMEkRpld1VPfJkAiQ0wLGDrGbtHILpI4/a+b3mVFq3kAbsONKmsPJ1O7ypMSmSb7Ci9/Z0bxDjXuzpXomt1Q4NkiOc64na7J7C5w+YLu/mhbiq/lf8r7FolgNVk2eVy+QGsPGO3ltbKoVLaeg/5tLIjs5GIraCO6Nvug0MbeR8UpbRv9R/ZE0v7NhN/BvazLxov+S5U19Xxy1wkEAlEApFAJBAJPCCQdRh/plLij+vuP3y8/xVgAAkZCFP/heS9AAAAAElFTkSuQmCC";

    public static byte[] getTestImageBuffer() {
        try {
            return Base64.getDecoder().decode(TEST_IMAGE_BASE_64.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        // 流程不会到此地，测试场景可接受
        return Base64.getDecoder().decode(TEST_IMAGE_BASE_64);
    }

    @Override
    public byte[] generatePreviewImage(String mimeType, byte[] data) {
        return getTestImageBuffer();
    }
}
