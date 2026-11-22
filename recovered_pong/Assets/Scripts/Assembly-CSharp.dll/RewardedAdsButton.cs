// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.UI;

public class RewardedAdsButton : MonoBehaviour, IUnityAdsLoadListener, IUnityAdsShowListener
{
    private Button _showAdButton;
    private string _androidAdUnitId;
    private string _iOSAdUnitId;
    private string _adUnitId;
    public GameObject Image;
    public GameObject AdsError;

    // RVA: 0x240c
    private void Awake()
    {
        this.Image.SetActive(false);
        this.AdsError.SetActive(false);
        this._showAdButton.onClick.AddListener(new UnityAction(this.showImage));
        this._adUnitId = this._androidAdUnitId;
    }

    // RVA: 0x2459
    public void LoadAd()
    {
        Debug.Log(string.Concat("Loading Ad: ", this._adUnitId));
        Advertisement.Load(this._adUnitId, this);
    }

    // RVA: 0x247c
    public void showImage()
    {
        this.Image.SetActive(true);
    }

    // RVA: 0x248c
    public void OnUnityAdsAdLoaded(string adUnitId)
    {
        Debug.Log(string.Concat("Ad Loaded: ", adUnitId));
        if (adUnitId.Equals(this._adUnitId)) {
            this._showAdButton.onClick.AddListener(new UnityAction(this.ShowAd));
            this._showAdButton.interactable = true;
        }
    }

    // RVA: 0x24df
    public void ShowAd()
    {
        this._showAdButton.interactable = false;
        Advertisement.Show(this._adUnitId, this);
        this.LoadAd();
    }

    // RVA: 0x2500
    public void OnUnityAdsShowComplete(string adUnitId, UnityAdsShowCompletionState showCompletionState)
    {
        if (adUnitId.Equals(this._adUnitId)) {
            if (showCompletionState.Equals(1)) {
                Debug.Log("Unity Ads Rewarded Ad Completed");
                Debug.Log("You have gained 10 coins well done");
                Advertisement.Load(this._adUnitId, this);
            }
        }
    }

    // RVA: 0x2550
    public void OnUnityAdsFailedToLoad(string adUnitId, UnityAdsLoadError error, string message)
    {
        string[] tmp0 = new string[6];
        tmp0[0] = "Error loading Ad Unit ";
        tmp0[1] = adUnitId;
        tmp0[2] = ": ";
        tmp0[3] = error.ToString();
        tmp0[4] = " - ";
        tmp0[5] = message;
        Debug.Log(string.Concat(tmp0));
        this.AdsError.SetActive(true);
    }

    // RVA: 0x25ac
    public void OnUnityAdsShowFailure(string adUnitId, UnityAdsShowError error, string message)
    {
        string[] tmp0 = new string[6];
        tmp0[0] = "Error showing Ad Unit ";
        tmp0[1] = adUnitId;
        tmp0[2] = ": ";
        tmp0[3] = error.ToString();
        tmp0[4] = " - ";
        tmp0[5] = message;
        Debug.Log(string.Concat(tmp0));
        this.AdsError.SetActive(true);
    }

    // RVA: 0x2605
    public void OnUnityAdsShowStart(string adUnitId)
    {
    }

    // RVA: 0x2607
    public void OnUnityAdsShowClick(string adUnitId)
    {
    }

    // RVA: 0x2609
    private void OnDestroy()
    {
        this._showAdButton.onClick.RemoveAllListeners();
    }

    // RVA: 0x261b
    public RewardedAdsButton()
    {
        this._androidAdUnitId = "Rewarded_Android";
        this._iOSAdUnitId = "Rewarded_iOS";
    }

}
