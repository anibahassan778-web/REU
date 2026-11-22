// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.UI;

public class BannerAds : MonoBehaviour
{
    private Button _loadBannerButton;
    private Button _showBannerButton;
    private Button _hideBannerButton;
    private BannerPosition _bannerPosition;
    private string _androidAdUnitId;
    private string _iOSAdUnitId;
    private string _adUnitId;

    // RVA: 0x21a4
    private void Start()
    {
        this._adUnitId = this._androidAdUnitId;
        Banner.SetPosition(this._bannerPosition);
        this.LoadBanner();
    }

    // RVA: 0x21c4
    public void LoadBanner()
    {
        BannerLoadOptions tmp0 = new BannerLoadOptions();
        tmp0.loadCallback = new LoadCallback(this.OnBannerLoaded);
        tmp0.errorCallback = new ErrorCallback(this.OnBannerError);
        BannerLoadOptions local0 = tmp0;
        Banner.Load(this._adUnitId, local0);
    }

    // RVA: 0x2207
    private void OnBannerLoaded()
    {
        this.ShowBannerAd();
        Debug.Log("Banner loaded");
    }

    // RVA: 0x2219
    private void OnBannerError(string message)
    {
        Debug.Log(string.Concat("Banner Error: ", message));
    }

    // RVA: 0x222c
    private void ShowBannerAd()
    {
        BannerOptions tmp0 = new BannerOptions();
        tmp0.clickCallback = new BannerCallback(this.OnBannerClicked);
        tmp0.hideCallback = new BannerCallback(this.OnBannerHidden);
        tmp0.showCallback = new BannerCallback(this.OnBannerShown);
        BannerOptions local0 = tmp0;
        Banner.Show(this._adUnitId, local0);
    }

    // RVA: 0x2281
    private void HideBannerAd()
    {
        Banner.Hide(false);
    }

    // RVA: 0x2289
    private void OnBannerClicked()
    {
    }

    // RVA: 0x228b
    private void OnBannerShown()
    {
    }

    // RVA: 0x228d
    private void OnBannerHidden()
    {
    }

    // RVA: 0x228f
    private void OnDestroy()
    {
    }

    // RVA: 0x2291
    public BannerAds()
    {
        this._bannerPosition = 4;
        this._androidAdUnitId = "Banner_Android";
        this._iOSAdUnitId = "Banner_iOS";
    }

}
