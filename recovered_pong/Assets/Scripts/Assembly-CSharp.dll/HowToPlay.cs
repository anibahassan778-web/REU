// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class HowToPlay : MonoBehaviour
{
    public Button yourButton;
    public GameObject Image2;

    // RVA: 0x2953
    private void Start()
    {
        this.Image2.SetActive(false);
        this.yourButton.GetComponent<Button>().onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x2982
    private void TaskOnClick()
    {
        this.Image2.SetActive(true);
        SceneManager.LoadScene("HowToPlay");
    }

}
