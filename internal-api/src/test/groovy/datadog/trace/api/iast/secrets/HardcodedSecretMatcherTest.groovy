package datadog.trace.api.iast.secrets

import spock.lang.Specification

import static datadog.trace.api.iast.secrets.HardcodedSecretMatcher.*

class HardcodedSecretMatcherTest extends Specification {

  void 'test matchers' (){

    //fake secret split in sampleFirst10 and sampleRest to avoid the secret to be detected by github secret scanner

    when:
    final matches = matcher.matches(sampleFirst10+sampleRest)

    then:
    matches

    where:
    matcher |  sampleRest | sampleFirst10
    ADOBE_CLIENT_SECRET | '7a35f0AF375A183d8dAF49C3C4' | 'p8e-ea4523'
    AGE_SECRET_KEY | '-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ' | 'AGE-SECRET'
    ALIBABA_ACCESS_KEY_ID | 's8bp3u203bqrmh' | 'LTAItg369c'
    AUTHRESS_SERVICE_CLIENT_ACCESS_KEY | 'ssm6idmao8bg4cwog8o.0ly2k1.acc_gj4od-aqtd8myn4wtqfn21s4ur.4i7xysqh4xw_h70emkz3m/3hofr_r5a8zxkj2vn+lhdb=u=ve0hx_5e8t0isbs5-x3m' | 'sc_n69s5dr'
    AWS_ACCESS_TOKEN | '4GMGU8S9KF' | 'A3T56TANU5'
    CLOJARS_API_TOKEN | 'djdlux73zfvhxl3eppl3wgvqbo6qt7w1f4zh59q4qijcmzp680q6q6w6yx' | 'CLOJARS_0i'
    DATABRICKS_API_TOKEN | '7b633eh90gh81cg9a0604fed61' | 'dapic50e61'
    DIGITALOCEAN_ACCESS_TOKEN | 'ee3c62c8d6a7c8adc3a5f92af8fe48fd78b152ced62fecfd455013bf6277d' | 'doo_v1_1e0'
    DIGITALOCEAN_PAT | '9912143ef1a992d9c28c691f72737ba540f01935f5bf06e596056cfa1c597' | 'dop_v1_5fb'
    DIGITALOCEAN_REFRESH_TOKEN | '6ad632305130d6d3b39d96e764c5fbf2d71cb1bcb95b19364ebeec1a60a80' | 'dor_v1_df1'
    DOPPLER_API_TOKEN | 'gljbrvs2dabfm2dlca8cibnqoyqh1g4d9p2ktv3' | 'dp.pt.eweb'
    DUFFEL_API_TOKEN | 't_3tcs-ydi5d6d0b5f_5z5flb_h-g9=54sirn3=9t4xiq' | 'duffel_tes'
    DYNATRACE_API_TOKEN | '5wrlo9pn95hhurlod3puc.4qi65si4ye41ko4t27qawulaz5fxpnxlim1acse6fegdn2g7utbsec6kfig9nuok' | 'dt0c01.d5k'
    EASYPOST_API_TOKEN | 'k3t5rniw7zid6mfgfqr54lfsuz6w4jik0c0yuho66vjjfrs7' | 'EZAKtt7w3r'
    FLUTTERWAVE_PUBLIC_KEY | 'ST-gg0c9393af6025309g72039a29ea7gbd-X' | 'FLWPUBK_TE'
    FRAMEIO_API_TOKEN | 'f4q4bv9yql6x5hf0n52prw=o95zixujk325vp5rccqfzqqch_uxu4tlv5c7i' | 'fio-u-nt83'
    GCP_API_KEY | '5PbHXOgK1RKzVlTG5xkUHRFlMyDAO' | 'AIzai1v3as'
    GITHUB_APP_TOKEN | 'aIlk2UMGTkC9WCDlpe9AjRNZa1WZQW' | 'ghu_39GyMb'
    GITHUB_FINE_GRAINED_PAT | '_VMGTQQzm696ggAKIXshhL_jEykw_CeFYMZtINz78MKuw2zE0rO_bTrnVt1lvkALTv8ui61Wok9w0wOBZHw' | 'github_pat'
    GITHUB_OAUTH | 'yfKdBNbYB0U5lTpRBQJVmoErmOO1WE' | 'gho_Odt4lB'
    GITHUB_PAT | 'nf597ah8u5ntrqWYk5AE9PUzavWCaI' | 'ghp_fBQzYM'
    GITLAB_PAT | 'WGJzpjOj1VD8ddYr' | 'glpat-bwYP'
    GITLAB_RRT | 'VvT-h2LnhGdYufAuClu' | 'GR1348941E'
    GRAFANA_API_KEY | 'VlNRzi0i6l0qAIr1rBbVdxHK8xtbvPpuaIIQv9MLfsslGxObwsncb1RieDSByZwr6GP19aGryQzuFfELjSoClNSj1jvsqKWF5ApAhWbVm6nLBgd88HcgvtFtxqAaoYAn3tyEzEnN1SapjHhUGxtjs0UL46M1VBrN5ARRrZJ4dPocTaJqIqT1pb4DYnJ31w1zlMtjzPmrnyFdL22ewfspszieFO3EPPDrYz1Grk12DlN0Vb1gupHVDLHvoOdkWhbYvYFWAwwLozlGlsJnXWUkIrnMrFcdGRgfKzF6Weg825rNkBK6F0NAN0QDv2p5UTFXTF5wmMQ20WrMAZmp5fXQEfSTiCLdzknjzT3eFXT2jUK==' | 'eyJrIjoiMu'
    GRAFANA_CLOUD_API_TOKEN | '0UzznOlByCQtIXsgHljMgruVbKf/1N2l0pAAFseJv3==' | 'glc_WR//07'
    GRAFANA_SERVICE_ACCOUNT_TOKEN | 'YbmaIkcWctkLkLo3dWqaaHYbn3b_aD529BdD' | 'glsa_BqWzJ'
    HASHICORP_TF_API_TOKEN | 'djit.atlasv1.0q1lbg4jfbcm9-hu2u0jsk4ed=l_0ngbxs_wngdbrylwk1tis0o2vm7qyxhc85v' | 'i5pwlaofyh'
    JWT | 'AOW18m0zEfEa.eyBFsOK9V_cjQeFUo_do3FZu6H-CBXYK.uI1CJC0BRDMzHjYFFO=' | 'ey1bSB8MlJ'
    LINEAR_API_KEY | '6hbqr9ghnpgv34moysa804csbt3aiu8ezhq66h' | 'lin_api_h7'
    NPM_ACCESS_TOKEN | 'txtu0e03jgbfto7gf82qc6voqsq9m2' | 'npm_xf1k0e'
    OPENAI_API_KEY | 'ECosBbzUvXKE9T3BlbkFJEqeNdignOTCDnDZl2d3R' | 'sk-ItwG2qC'
    PLANETSCALE_API_TOKEN | '_59.j4niv2=s5d6a49ku9=a.1oa_i6hjm5uel=l8_eymq3' | 'pscale_tkn'
    PLANETSCALE_OAUTH_TOKEN | 'th_cxbirnget59m93o.3u0g.w127yqv5m1w_0_phx49f1anpypexe853x' | 'pscale_oau'
    PLANETSCALE_PASSWORD | 'nj4rx.2g0q86hcbl96fok5u7.6==g=uylmfbmhsfpb.my0a' | 'pscale_pw_'
    PREFECT_API_TOKEN | '9klt8733s80dj9b9mmz442tt64clk3' | 'pnu_xyy0hr'
    PRIVATE_KEY | 'SDC5BPRIVATE KEY------- -KEY----' | '-----BEGIN'
    PYPI_UPLOAD_TOKEN | 'HlwaS5vcmc-5ntvFVbG5vofVEq8FJMbR9unZJkZFcbrDXIv6bpcN_9w5L9kZtnA' | 'pypi-AgEIc'
    README_API_TOKEN | 'm71aaql8q977bxr1ij98zhgaan3o5hxh43dzyg9sp809wqy8sbwp77ne11axw6cil' | 'rdme_4z76d'
    RUBYGEMS_API_TOKEN | 'd36bae1261df247873de80998cf728148010593c1044201' | 'rubygems_b'
    SCALINGO_API_TOKEN | 'fElYN1l2-N-3_HDknDl5af0FZtzFTwgIQ2LBmRX4WUk0' | 'tk-us-X_nP'
    SENDGRID_API_TOKEN | '6mza01.mr1fzurif_b-v1mfk=acb0ovqx63oiw5u2srcxvl.wud=njyugod' | 'SG.4zb_m4m'
    SENDINBLUE_API_TOKEN | '4bc5a5578fd17d7b4f5b1ce3afddc71c76608713e535480f4b559311624522-d6evcprg45e21vu8' | 'xkeysib-49'
    SHIPPO_API_TOKEN | 'e_872bc6ceef3fc6b6a172ef8c8777648d3264938c' | 'shippo_liv'
    SHOPIFY_ACCESS_TOKEN | '180a41fb8DaeEc5cd1c03fCC3A3E' | 'shpat_d042'
    SHOPIFY_CUSTOM_ACCESS_TOKEN | 'Db0D9Cb8e1243E8f89FEe9CFeBD7' | 'shpca_Fb9a'
    SHOPIFY_PRIVATE_APP_ACCESS_TOKEN | 'daae3efa1F142964eE00F5E3af18' | 'shppa_2DBa'
    SHOPIFY_SHARED_SECRET | 'Ee1f50D1f05c3Faac2ef0b588bba' | 'shpss_4495'
    SLACK_APP_TOKEN | '8-9-3m4nq' | 'xapp-0-8OF'
    SLACK_BOT_TOKEN | '54902-2464785361518HyHvkBIs' | 'xoxb-15227'
    SLACK_CONFIG_ACCESS_TOKEN | '6-ADPTFBG55CX85BUF049LCDMPWLWY1GPFZM0FB06X2TK6O9D12ZWV7KY60A73PRDZXSITKODA8LTT2QBWH46CT3HOY3OB2DIKPVI60UQNVWXV1EDK8LVUVBF3K45Q9CH4DIDHHF5XOSR7FWXT0ECRRXYOPPV6Y9NTO2SSB' | 'xoxe.xoxb-'
    SLACK_CONFIG_REFRESH_TOKEN | 'Y1CH1SXAGCSPRP4J2SV769RZTX337K6RXAE904ZRKNZVODIB0A959CFYJNYDZ4C5GB2RK5S83EWSK6KPP2C7KBSU4QZNFV1QL3PNL105RAB5QWNNCJY71EUFSHX4FF2HMFHQR7DYBGQJ9XE' | 'xoxe-7-U9C'
    SLACK_LEGACY_BOT_TOKEN | '8612-0hg3TuoZGrz43jja3JPs' | 'xoxb-03399'
    SLACK_LEGACY_TOKEN | '-9-BFa' | 'xoxo-919-6'
    SLACK_LEGACY_WORKSPACE_TOKEN | 'QSjus3e' | 'xoxa-8-rLN'
    SLACK_USER_TOKEN | '81272010-2723063352185-7296143369223-fDEmEz6Us9YZMb5SsErsEIuBbMI3evfVpD' | 'xoxp-34943'
    SLACK_WEBHOOK_URL | 'oks.slack.com/services/XssFMcvHiQCwOpnXsIajWjSGG65BipsmGAadvDjcNA9rEN' | 'https://ho'
    SQUARE_ACCESS_TOKEN | '46fYcoNy8f989Ena4Ch' | 'sq0atp-uVR'
    SQUARE_SECRET | 'x2id4UZI5swWWOMU582lpMUOOIa04CHr40jWwAIT' | 'sq0csp-c06'
    STRIPE_ACCESS_TOKEN | 'a8p96ppl3k9akka8sxj7w' | 'sk_test_c1'
    TWILIO_API_KEY | '7AFB6C77dFFF4fEE0b2E24bf' | 'SKDE6fDCca'
    VAULT_BATCH_TOKEN | 'mdhtm-0wj0d5i3qj2pv2fzyl81y2qz28llp6mdzk3dg5ien2t6w0p4qzq7wxn9gv9gt8ow9z1l_bs5c4o7jwdcst71pqsb959x7bwqo889_d6ohx54jr0wr7zabygsg0oee79ah9smzgdpyst3xz4rfnfaowp11p2k11km04wl_n9l4knufp7s79-328yxu3wjq408ihd4' | 'hvb.dmd-vw'
    VAULT_SERVICE_TOKEN | '0nsywcmblez3j7raibo0n1ynfil_yr85zdkx1bm6zs53qpk4nygpejv_0fto2np6i6dgx6lg0ta677819tw-ks0qul9wu' | 'hvs.ypqt_8'
  }
}
